"""Dataclasses shared by every stage of the pipeline.

See `docs/data-model.md`. MediaInfo is frozen and kept separate from JobSpec
because probing is expensive and the same file gets planned several times
(preview, real run, a second mode) — probe once, plan many.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

# Containers we accept as input (R1, lowercase, no dot).
INPUT_EXTS = frozenset(
    {"mp4", "mov", "mkv", "avi", "3gp", "3g2", "webm", "m4v", "mts", "m2ts", "wmv", "flv"}
)

# Source container -> output container (R1.3).
_MKV_FAMILY = frozenset({"mkv", "webm"})

MODES = ("low", "medium", "high", "archive")


def output_container(source_ext: str) -> str:
    """R1.3 — MKV-family sources stay MKV, everything else becomes MP4."""
    return "mkv" if source_ext.lower().lstrip(".") in _MKV_FAMILY else "mp4"


@dataclass(frozen=True)
class MediaInfo:
    path: Path
    size: int                    # bytes
    duration: float              # seconds
    width: int                   # coded width, before rotation is applied
    height: int
    rotation: int                # 0 / 90 / 180 / 270, from the display matrix
    fps: float
    vcodec: str                  # 'h264', 'hevc', 'av1', ...
    acodec: str | None
    vbitrate: int                # bits/s
    mtime: float
    container: str               # source extension, lowercased, no dot

    @property
    def display_width(self) -> int:
        return self.height if self.rotation in (90, 270) else self.width

    @property
    def display_height(self) -> int:
        return self.width if self.rotation in (90, 270) else self.height

    @property
    def pixels_per_second(self) -> float:
        return float(self.width) * self.height * self.fps

    @property
    def bpp(self) -> float:
        """Bits per pixel per frame — the basis of the suggestion rank (R10.3).

        Zero when we cannot tell, which ranks the file as 'not worth it' rather
        than inventing a saving we cannot back up.
        """
        pps = self.pixels_per_second
        return (self.vbitrate / pps) if pps > 0 and self.vbitrate > 0 else 0.0


@dataclass(frozen=True)
class Caps:
    """Probed once per ffmpeg version, then cached in settings.json (R4.3)."""

    ffmpeg: Path
    ffprobe: Path
    ffmpeg_version: str
    encoders: frozenset[str] = frozenset()
    vaapi_device: Path | None = None
    probed_at: float = 0.0

    def has(self, encoder: str) -> bool:
        return encoder in self.encoders


@dataclass(frozen=True)
class JobSpec:
    src: MediaInfo
    dst: Path
    mode: str = "medium"
    crf: int | None = None       # overrides the ladder (R3.4)
    hw: str = "off"              # 'off' | 'auto'  (R4.2)
    audio: str = "keep"          # 'keep' (copy) | '192k' | '160k' | '128k' (R6.3)
    delete_original: bool = False

    def __post_init__(self) -> None:
        # Invariants 1 and 2 from docs/data-model.md, enforced where they are
        # cheapest to enforce: at construction, not at delete time.
        if self.mode not in MODES:
            raise ValueError(f"unknown mode {self.mode!r}; expected one of {MODES}")
        if self.dst.parent != self.src.path.parent:
            raise ValueError("output must live beside the original (R1.1)")
        if self.dst == self.src.path:
            raise ValueError("output would overwrite the original (R2.1)")


@dataclass
class JobResult:
    spec: JobSpec
    status: str                  # ok | grew | failed | cancelled | skipped
    dst_size: int | None = None
    seconds: float = 0.0
    verified: bool = False
    deleted: bool = False
    message: str = ""

    @property
    def ratio(self) -> float | None:
        """Output size as a fraction of the input. None when nothing was written."""
        if self.dst_size is None or self.spec.src.size == 0:
            return None
        return self.dst_size / self.spec.src.size

    @property
    def saved(self) -> int | None:
        if self.dst_size is None:
            return None
        return self.spec.src.size - self.dst_size


@dataclass(frozen=True)
class ScanEntry:
    info: MediaInfo
    bpp: float
    est_saving: int              # bytes — an estimate, always labelled (R10.4)
    rank: int = 0                # 0 = compress this one first
