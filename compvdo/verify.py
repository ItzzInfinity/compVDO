"""R8 — the checks that gate deleting an original.

All three must pass. A failure keeps both files and reports why; it is never a
reason to delete anything.
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path

from .cpu import budget as cpu_budget
from .model import Caps, MediaInfo
from .probe import ProbeError, info as probe_info

DURATION_ABS_TOLERANCE = 0.1      # seconds
DURATION_REL_TOLERANCE = 0.005    # 0.5 %


@dataclass(frozen=True)
class Verdict:
    ok: bool
    reasons: tuple[str, ...] = ()

    def __bool__(self) -> bool:
        return self.ok


def check(src: MediaInfo, dst: Path, caps: Caps, *, deep: bool = True,
          cores: int | None = None) -> Verdict:
    """Compare an output against its source. `deep=False` skips the decode pass."""
    problems: list[str] = []

    try:
        out = probe_info(dst, caps)
    except ProbeError as e:
        return Verdict(False, (f"output is not readable media: {e}",))

    # R8.1 — duration
    tol = max(DURATION_ABS_TOLERANCE, src.duration * DURATION_REL_TOLERANCE)
    delta = abs(out.duration - src.duration)
    if delta > tol:
        problems.append(
            f"duration differs by {delta:.2f}s (source {src.duration:.2f}s, "
            f"output {out.duration:.2f}s, tolerance {tol:.2f}s)"
        )

    # R8.2 — dimensions. Compared as *displayed*, not as coded: ffmpeg's
    # -autorotate bakes rotation into the pixels, so a 1280x720 clip tagged 270
    # legitimately comes out coded 720x1280 with no rotation tag. Comparing
    # coded dimensions would fail every portrait phone video.
    if (out.display_width, out.display_height) != (src.display_width, src.display_height):
        problems.append(
            f"display size changed: {src.display_width}x{src.display_height} -> "
            f"{out.display_width}x{out.display_height}"
        )

    # R8.2 — audio presence. We never silently drop a soundtrack.
    if src.acodec is not None and out.acodec is None:
        problems.append("source had audio, output has none")

    # R8.3 — full decode
    if deep:
        proc = subprocess.run(
            [str(caps.ffmpeg), "-hide_banner", "-nostdin", "-v", "error",
             "-threads", str(cpu_budget(cores)),
             "-i", str(dst), "-f", "null", "-"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if proc.returncode != 0 or proc.stderr.strip():
            tail = "; ".join(proc.stderr.strip().splitlines()[-3:]) or "decode failed"
            problems.append(f"decode errors: {tail}")

    return Verdict(not problems, tuple(problems))
