"""ffprobe wrapper and encoder capability detection.

Everything that asks ffmpeg a question lives here. Everything that asks it to
do work lives in encode.py.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
from fractions import Fraction
from pathlib import Path

from .model import Caps, MediaInfo

# Encoders we care about; anything else ffmpeg reports is ignored.
_WANTED = (
    "libx265", "libx264", "libsvtav1", "libaom-av1", "ffv1",
    "hevc_vaapi", "hevc_nvenc", "hevc_qsv", "hevc_amf", "hevc_videotoolbox",
)


class FFmpegMissing(RuntimeError):
    """ffmpeg or ffprobe is not on PATH. Carries an actionable message."""


class ProbeError(RuntimeError):
    """ffprobe ran but the file is not usable media."""


def _bundled_dir() -> Path | None:
    """Windows one-folder builds ship ffmpeg beside the executable."""
    import sys

    base = getattr(sys, "_MEIPASS", None)
    if base:
        d = Path(base) / "ffmpeg_bin"
        if d.is_dir():
            return d
    d = Path(__file__).resolve().parent.parent / "ffmpeg_bin"
    return d if d.is_dir() else None


def _find(name: str) -> Path:
    exe = name + (".exe" if os.name == "nt" else "")
    bundled = _bundled_dir()
    if bundled and (bundled / exe).exists():
        return bundled / exe
    found = shutil.which(name)
    if not found:
        raise FFmpegMissing(
            f"{name} was not found.\n"
            "  Ubuntu/Debian: sudo apt install ffmpeg\n"
            "  Fedora:        sudo dnf install ffmpeg\n"
            "  Arch:          sudo pacman -S ffmpeg\n"
            "  Windows:       put ffmpeg.exe in the ffmpeg_bin folder, or on PATH"
        )
    return Path(found)


def _run(argv: list[str], timeout: float = 60.0) -> subprocess.CompletedProcess:
    return subprocess.run(
        argv, capture_output=True, text=True, timeout=timeout,
        encoding="utf-8", errors="replace",
    )


# --------------------------------------------------------------------------
# Capabilities
# --------------------------------------------------------------------------

def detect_caps() -> Caps:
    """Probe ffmpeg once. Callers should prefer settings.cached_caps()."""
    ffmpeg, ffprobe = _find("ffmpeg"), _find("ffprobe")

    ver = _run([str(ffmpeg), "-hide_banner", "-version"])
    m = re.search(r"ffmpeg version (\S+)", ver.stdout)
    version = m.group(1) if m else "unknown"

    enc = _run([str(ffmpeg), "-hide_banner", "-encoders"])
    found = set()
    for line in enc.stdout.splitlines():
        # Format: " V..... libx265   libx265 H.265 / HEVC (codec hevc)"
        parts = line.split()
        if len(parts) >= 2 and parts[0].startswith("V") and parts[1] in _WANTED:
            found.add(parts[1])

    device = None
    if any(e.endswith("_vaapi") for e in found):
        for cand in sorted(Path("/dev/dri").glob("renderD*")) if Path("/dev/dri").is_dir() else []:
            if os.access(cand, os.R_OK | os.W_OK):
                device = cand
                break

    return Caps(
        ffmpeg=ffmpeg, ffprobe=ffprobe, ffmpeg_version=version,
        encoders=frozenset(found), vaapi_device=device, probed_at=time.time(),
    )


# --------------------------------------------------------------------------
# Media info
# --------------------------------------------------------------------------

def _fps(stream: dict) -> float:
    """avg_frame_rate is a rational string like '30000/1001'; it can be '0/0'."""
    for key in ("avg_frame_rate", "r_frame_rate"):
        raw = stream.get(key) or ""
        try:
            f = Fraction(raw)
        except (ZeroDivisionError, ValueError):
            continue
        if f > 0:
            return float(f)
    return 0.0


def _rotation(stream: dict) -> int:
    """Rotation can arrive three different ways depending on ffprobe version.

    The display matrix side-data is authoritative and negative-clockwise; the
    tag is the legacy path. Getting this wrong is how portrait phone clips come
    out sideways (R5.1), so we check every source.
    """
    for sd in stream.get("side_data_list") or []:
        if "rotation" in sd:
            return int(-float(sd["rotation"])) % 360
    tag = (stream.get("tags") or {}).get("rotate")
    if tag is not None:
        try:
            return int(float(tag)) % 360
        except ValueError:
            pass
    return 0


def info(path: Path, caps: Caps | None = None) -> MediaInfo:
    """Probe one file. Raises ProbeError when there is no usable video stream."""
    caps = caps or detect_caps()
    path = Path(path)
    proc = _run([
        str(caps.ffprobe), "-v", "error", "-print_format", "json",
        "-show_format", "-show_streams", str(path),
    ])
    if proc.returncode != 0:
        # ffprobe puts the full path in its message; strip it so the caller,
        # which already names the file, does not print it twice.
        tail = proc.stderr.strip().splitlines()[-1] if proc.stderr.strip() else "ffprobe failed"
        raise ProbeError(tail.replace(f"{path}: ", "").strip())
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise ProbeError(f"unreadable ffprobe output ({e})") from e

    streams = data.get("streams") or []
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    if video is None:
        raise ProbeError("no video stream")
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)

    fmt = data.get("format") or {}
    stat = path.stat()
    size = int(fmt.get("size") or stat.st_size)

    duration = 0.0
    for src in (fmt.get("duration"), video.get("duration")):
        try:
            duration = float(src)
        except (TypeError, ValueError):
            continue
        if duration > 0:
            break

    # Per-stream bit_rate is absent in plenty of phone MP4s; fall back to the
    # container rate, then to size/duration. Never leave it at zero if we can
    # compute it, because bpp==0 silently means "don't bother compressing".
    vbitrate = 0
    for src in (video.get("bit_rate"), fmt.get("bit_rate")):
        try:
            vbitrate = int(src)
        except (TypeError, ValueError):
            continue
        if vbitrate > 0:
            break
    if vbitrate <= 0 and duration > 0:
        vbitrate = int(size * 8 / duration)

    return MediaInfo(
        path=path,
        size=size,
        duration=duration,
        width=int(video.get("width") or 0),
        height=int(video.get("height") or 0),
        rotation=_rotation(video),
        fps=_fps(video),
        vcodec=(video.get("codec_name") or "").lower(),
        acodec=(audio.get("codec_name") or "").lower() if audio else None,
        vbitrate=vbitrate,
        mtime=stat.st_mtime,
        container=path.suffix.lower().lstrip("."),
    )
