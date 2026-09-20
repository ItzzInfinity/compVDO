"""QThread bridges over the core. No encoding logic lives here.

Everything in this file exists to move work off the UI thread and turn core
callbacks into Qt signals (R12.1). If you find yourself building an ffmpeg
argument here, it belongs in plan.py.
"""

from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QObject, QThread, Signal

from ..batch import run_batch
from ..encode import CancelToken
from ..model import Caps, JobResult, JobSpec
from ..scan import scan


class ScanWorker(QThread):
    """Probe a folder off the UI thread. ffprobe is ~20ms/file, which is still
    ten seconds on a folder of 500 — far too long to block on."""

    progress = Signal(int, int, str)          # done, total, current name
    finished_ok = Signal(list, list)          # entries, skipped
    failed = Signal(str)

    def __init__(self, root: Path, caps: Caps, mode: str, recursive: bool = True,
                 parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._root, self._caps, self._mode, self._recursive = root, caps, mode, recursive

    def run(self) -> None:
        try:
            entries, skipped = scan(
                self._root, self._caps, mode=self._mode, recursive=self._recursive,
                on_file=lambda n, total, p: self.progress.emit(n, total, p.name),
            )
        except Exception as e:                # noqa: BLE001 - a crashed scan must
            self.failed.emit(str(e))          # surface in the UI, not the console
            return
        self.finished_ok.emit(entries, skipped)


class EncodeWorker(QThread):
    """Run a batch, emitting progress. Cancellation goes through CancelToken,
    never through terminating the thread."""

    file_progress = Signal(int, int, str, float)   # index, total, name, fraction
    file_done = Signal(object)                     # JobResult
    all_done = Signal(list)                        # list[JobResult]
    failed = Signal(str)

    def __init__(self, specs: list[JobSpec], caps: Caps, *, resume_in: Path | None,
                 purge: bool = False, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._specs, self._caps = specs, caps
        self._resume_in, self._purge = resume_in, purge
        self.cancel_token = CancelToken()

    def cancel(self) -> None:
        """Safe to call from the UI thread; CancelToken wraps an Event."""
        self.cancel_token.cancel()

    def run(self) -> None:
        try:
            results = run_batch(
                self._specs, self._caps,
                on_progress=lambda i, t, spec, frac, secs:
                    self.file_progress.emit(i, t, spec.src.path.name, frac),
                on_done=lambda i, t, result: self.file_done.emit(result),
                cancel=self.cancel_token,
                resume_in=self._resume_in,
                purge=self._purge,
            )
        except Exception as e:                # noqa: BLE001
            self.failed.emit(str(e))
            return
        self.all_done.emit(results)


class PreviewWorker(QThread):
    """R11 — cut a short sample and encode only that."""

    progress = Signal(float)
    finished_ok = Signal(object, object, float)   # sample path, encoded path, ratio
    failed = Signal(str)

    def __init__(self, src_path: Path, caps: Caps, mode: str, hw: str,
                 seconds: float = 10.0, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._path, self._caps = src_path, caps
        self._mode, self._hw, self._seconds = mode, hw, seconds

    def run(self) -> None:
        import subprocess
        import tempfile

        from ..encode import run as encode_run
        from ..plan import target_container
        from ..probe import info

        try:
            src = info(self._path, self._caps)
            start = max(0.0, src.duration / 3)
            length = min(self._seconds, max(1.0, src.duration - start))
            tmpdir = Path(tempfile.mkdtemp(prefix="compvdo-preview-"))
            sample = tmpdir / f"sample{self._path.suffix}"
            # R11.2 - never write next to the user's original.
            subprocess.run(
                [str(self._caps.ffmpeg), "-hide_banner", "-nostdin", "-v", "error", "-y",
                 "-ss", f"{start:.2f}", "-t", f"{length:.2f}", "-i", str(src.path),
                 "-c", "copy", str(sample)], check=True, capture_output=True)

            cut = info(sample, self._caps)
            out = tmpdir / f"sample_compressed.{target_container(cut.container, self._mode)}"
            spec = JobSpec(src=cut, dst=out, mode=self._mode, hw=self._hw)
            outcome = encode_run(spec, self._caps,
                                 on_progress=lambda f, s: self.progress.emit(f))
            if not outcome.ok:
                self.failed.emit(outcome.stderr_tail or "preview encode failed")
                return
            self.finished_ok.emit(sample, out, out.stat().st_size / max(1, cut.size))
        except Exception as e:                # noqa: BLE001
            self.failed.emit(str(e))
