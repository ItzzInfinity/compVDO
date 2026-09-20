"""Queue several jobs, survive a Ctrl-C, and never let one file stop the rest (R9)."""

from __future__ import annotations

import json
import os
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

from .encode import CancelToken, cleanup_stale_temps, run as encode_run
from .model import Caps, JobResult, JobSpec, MediaInfo
from .plan import PlanError, existing_output, output_path, target_container
from .trash import TrashError, send as trash_send
from .verify import check

RUN_FILE = ".compvdo-run.json"

# (index, total, spec, fraction, seconds_encoded)
ProgressFn = Callable[[int, int, JobSpec, float, float], None]
DoneFn = Callable[[int, int, JobResult], None]


@dataclass
class RunState:
    """Resume record, stored beside the media so it travels with the folder."""

    path: Path
    started: float
    mode: str
    done: dict[str, dict]
    failed: dict[str, str]

    @classmethod
    def load(cls, folder: Path, mode: str) -> RunState:
        p = Path(folder) / RUN_FILE
        if p.exists():
            try:
                blob = json.loads(p.read_text(encoding="utf-8"))
                if blob.get("mode") == mode:
                    return cls(p, blob.get("started", time.time()), mode,
                               blob.get("done", {}), blob.get("failed", {}))
            except (OSError, json.JSONDecodeError):
                pass          # a corrupt run file just means 'start over'
        return cls(p, time.time(), mode, {}, {})

    def save(self) -> None:
        try:
            tmp = self.path.with_suffix(".json.tmp")
            tmp.write_text(json.dumps({
                "started": self.started, "mode": self.mode,
                "done": self.done, "failed": self.failed,
            }, indent=2), encoding="utf-8")
            os.replace(tmp, self.path)
        except OSError:
            pass          # an unwritable folder costs us resume, not the run

    def clear(self) -> None:
        self.path.unlink(missing_ok=True)


def plan_jobs(infos: Sequence[MediaInfo], *, mode: str = "medium",
              hw: str = "off", container: str | None = None,
              delete_original: bool = False, again: bool = False,
              ) -> tuple[list[JobSpec], list[tuple[Path, Path]]]:
    """Returns (jobs to run, [(source, existing output)] that were skipped).

    A source that already has a `_compressed` sibling is skipped unless
    `again=True`; otherwise re-running on a folder fills it with `(2)` copies.
    """
    specs, already = [], []
    for i in infos:
        ext = target_container(i.container, mode, container)
        if not again and (prev := existing_output(i.path, ext)):
            already.append((i.path, prev))
            continue
        specs.append(JobSpec(src=i, dst=output_path(i.path, ext), mode=mode,
                             hw=hw, delete_original=delete_original))
    return specs, already


def run_batch(specs: Sequence[JobSpec], caps: Caps, *,
              on_progress: ProgressFn | None = None,
              on_done: DoneFn | None = None,
              cancel: CancelToken | None = None,
              resume_in: Path | None = None,
              purge: bool = False,
              deep_verify: bool = True) -> list[JobResult]:
    """Encode each spec in turn. Returns one JobResult per spec, in order."""
    total = len(specs)
    state = RunState.load(resume_in, specs[0].mode if specs else "medium") if resume_in else None
    results: list[JobResult] = []

    for idx, spec in enumerate(specs, 1):
        key = str(spec.src.path.resolve())

        if state and key in state.done:
            results.append(JobResult(spec, "skipped",
                                     dst_size=state.done[key].get("dst_size"),
                                     message="already done in an earlier run"))
            if on_done:
                on_done(idx, total, results[-1])
            continue

        if cancel is not None and cancel.cancelled:
            results.append(JobResult(spec, "cancelled", message="batch cancelled"))
            if on_done:
                on_done(idx, total, results[-1])
            continue

        result = _run_one(spec, caps, idx, total, on_progress, cancel, purge, deep_verify)
        results.append(result)

        if state is not None:
            if result.status in ("ok", "grew"):
                state.done[key] = {"status": result.status, "dst_size": result.dst_size}
            elif result.status == "failed":
                state.failed[key] = result.message
            state.save()

        if on_done:
            on_done(idx, total, result)

    if state is not None and not state.failed and \
            all(r.status in ("ok", "grew", "skipped") for r in results):
        state.clear()          # nothing left to resume

    return results


def _run_one(spec: JobSpec, caps: Caps, idx: int, total: int,
             on_progress: ProgressFn | None, cancel: CancelToken | None,
             purge: bool, deep_verify: bool) -> JobResult:
    """One file, with every failure turned into a JobResult (R9.3)."""
    try:
        outcome = encode_run(
            spec, caps, cancel=cancel,
            on_progress=(lambda f, s: on_progress(idx, total, spec, f, s)) if on_progress else None,
        )
    except PlanError as e:
        return JobResult(spec, "failed", message=str(e))
    except OSError as e:
        return JobResult(spec, "failed", message=f"could not write output: {e}")

    if outcome.cancelled:
        return JobResult(spec, "cancelled", seconds=outcome.seconds, message="cancelled")
    if not outcome.ok:
        return JobResult(spec, "failed", seconds=outcome.seconds,
                         message=outcome.stderr_tail or "ffmpeg failed with no output")

    dst_size = spec.dst.stat().st_size
    status = "ok" if dst_size < spec.src.size else "grew"          # R7.2
    result = JobResult(spec, status, dst_size=dst_size, seconds=outcome.seconds,
                       message="; ".join(outcome.plan.notes))

    verdict = check(spec.src, spec.dst, caps, deep=deep_verify)    # R8
    result.verified = bool(verdict)
    if not verdict:
        result.message = "; ".join((*verdict.reasons, result.message)).strip("; ")

    # R8.4 / R7.2 — a delete needs a passed verification AND a real saving.
    if spec.delete_original:
        if not result.verified:
            result.message = f"original KEPT, verification failed: {result.message}"
        elif status == "grew":
            result.message = "original KEPT: the output is not smaller"
        else:
            try:
                what = trash_send(spec.src.path, purge=purge)
                result.deleted = True
                result.message = f"original {what}; {result.message}".strip("; ")
            except (TrashError, OSError) as e:
                result.message = f"original KEPT, could not delete: {e}"

    return result


def prepare(folder: Path) -> int:
    """Clear temp files left behind by a crashed run. Returns how many."""
    return cleanup_stale_temps(Path(folder))
