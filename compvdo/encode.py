"""Run ffmpeg: temp-file discipline, real progress, and a cancel that works.

Nothing here prints. Progress and log lines go out through callbacks so the CLI
and the GUI can both consume them (see architecture.md).
"""

from __future__ import annotations

import os
import subprocess
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .model import Caps, JobSpec
from .plan import Plan, build

ProgressFn = Callable[[float, float], None]   # (fraction 0..1, seconds encoded)

_TMP_PREFIX = ".compvdo-tmp-"
# ffmpeg takes ~1.2s to exit on SIGTERM because it flushes and finalises the
# output. On cancel we throw that output away, so the graceful path buys us
# nothing and would blow the 1s budget in R12.2 on its own. Short grace, then
# SIGKILL.
_KILL_GRACE = 0.25


class CancelToken:
    """Thread-safe cancel flag. One per job; the GUI holds the other end."""

    def __init__(self) -> None:
        self._event = threading.Event()

    def cancel(self) -> None:
        self._event.set()

    @property
    def cancelled(self) -> bool:
        return self._event.is_set()


@dataclass
class EncodeOutcome:
    ok: bool
    cancelled: bool
    seconds: float
    stderr_tail: str
    plan: Plan


def temp_path_for(dst: Path) -> Path:
    """Temp file beside the destination, so os.replace() is atomic.

    Invariant 2 in data-model.md: a temp file in /tmp would cross a filesystem
    boundary and turn the final move into a non-atomic copy.
    """
    return dst.with_name(f"{_TMP_PREFIX}{os.getpid()}{dst.suffix}")


def _with_stats_period(argv: list[str], period: str = "0.25") -> list[str]:
    """Ask ffmpeg for 4 progress blocks a second instead of 1.

    Purely cosmetic - it makes the GUI bar move smoothly. Cancel latency does
    not depend on it (see the watcher thread in run()).
    """
    out = list(argv)
    out.insert(out.index("-progress"), "-stats_period")
    out.insert(out.index("-progress"), period)
    return out


def _parse_progress(line: str) -> tuple[str, str] | None:
    key, sep, value = line.strip().partition("=")
    return (key, value) if sep else None


def run(spec: JobSpec, caps: Caps, *,
        on_progress: ProgressFn | None = None,
        cancel: CancelToken | None = None,
        duration_override: float | None = None,
        cores: int | None = None,
        preset: str | None = None) -> EncodeOutcome:
    """Encode one file. The original is never touched (R2.1)."""
    tmp = temp_path_for(spec.dst)
    plan = build(spec, caps, tmp, cores, preset)
    duration = duration_override or spec.src.duration
    started = time.monotonic()

    proc = subprocess.Popen(
        _with_stats_period(plan.argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )

    # stderr must be drained on its own thread: ffmpeg blocks once the pipe
    # buffer fills, which on a long encode means a silent hang, not an error.
    stderr_lines: list[str] = []

    def _drain() -> None:
        assert proc.stderr is not None
        for line in proc.stderr:
            stderr_lines.append(line.rstrip())
            del stderr_lines[:-40]          # keep only the tail we will show

    drainer = threading.Thread(target=_drain, daemon=True)
    drainer.start()

    killed = False

    # Cancel is watched on its own thread rather than between progress lines.
    # ffmpeg emits a progress block roughly once a second, so polling the token
    # inside the read loop made the stop latency depend on ffmpeg's cadence
    # (measured: 1.38s, over the 1s budget in R12.2). Killing the process from
    # outside closes stdout, which ends the read loop immediately.
    watcher_done = threading.Event()

    def _watch() -> None:
        while not watcher_done.wait(0.05):
            if cancel is not None and cancel.cancelled:
                nonlocal_killed.append(True)
                _stop(proc)
                return

    nonlocal_killed: list[bool] = []
    watcher = threading.Thread(target=_watch, daemon=True)
    if cancel is not None:
        watcher.start()
    try:
        assert proc.stdout is not None
        for raw in proc.stdout:
            kv = _parse_progress(raw)
            if not kv or on_progress is None:
                continue
            key, value = kv
            if key == "out_time_us" and duration > 0:
                try:
                    secs = int(value) / 1_000_000
                except ValueError:
                    continue
                on_progress(min(1.0, secs / duration), secs)
            elif key == "progress" and value == "end":
                on_progress(1.0, duration)
    finally:
        watcher_done.set()
        # The watcher owns stopping the process; calling _stop() here too would
        # have two threads in proc.wait(timeout=...) at once.
        if cancel is not None and cancel.cancelled and not nonlocal_killed:
            _stop(proc)
        killed = bool(nonlocal_killed) or (cancel is not None and cancel.cancelled)
        returncode = proc.wait()
        drainer.join(timeout=2.0)

    elapsed = time.monotonic() - started
    ok = (not killed) and returncode == 0 and tmp.exists() and tmp.stat().st_size > 0

    if ok:
        # Atomic within the directory; replaces nothing, since output_path()
        # already guaranteed the name is free (R1.2).
        os.replace(tmp, spec.dst)
        # R5.3 — carry the original's mtime across.
        os.utime(spec.dst, (spec.src.mtime, spec.src.mtime))
    else:
        tmp.unlink(missing_ok=True)          # R2.4

    return EncodeOutcome(
        ok=ok, cancelled=killed, seconds=elapsed,
        stderr_tail="\n".join(stderr_lines[-20:]), plan=plan,
    )


def _stop(proc: subprocess.Popen) -> None:
    """SIGTERM, one second of grace, then SIGKILL (R12.2)."""
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=_KILL_GRACE)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=_KILL_GRACE)


def _pid_of_temp(path: Path) -> int | None:
    """The owning process id encoded in a temp filename, if it parses."""
    stem = path.name[len(_TMP_PREFIX):].split(".")[0]
    return int(stem) if stem.isdigit() else None


def _process_alive(pid: int) -> bool:
    """True if a process with this id exists (any owner)."""
    if os.name == "nt":                         # no /proc; assume alive
        return True                             # and let the owner clean up
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True                             # exists, just not ours
    return True


def cleanup_stale_temps(folder: Path) -> int:
    """Remove temp files left by a *crashed* run. Returns how many went.

    Only touches temps whose owning process is gone. Deleting indiscriminately
    destroys in-flight encodes: a second compvdo instance, or the GUI merely
    scanning the same folder, used to unlink the temp file of a running job.
    ffmpeg keeps writing happily to the unlinked inode, so the encode appears
    to succeed and then the output simply is not there.
    """
    n = 0
    for f in folder.glob(f"{_TMP_PREFIX}*"):
        pid = _pid_of_temp(f)
        if pid is not None and pid != os.getpid() and _process_alive(pid):
            continue                            # someone is still writing it
        try:
            f.unlink()
            n += 1
        except OSError:
            pass
    return n
