"""Turn (MediaInfo, mode, hw) into a concrete ffmpeg argv — and nothing else.

`build()` is a pure function: no I/O, no side effects, no globals. That is
deliberate and it is the single most important design decision in the project.
It means the whole encoder / container / rotation / audio matrix is covered by
unit tests that run in milliseconds without encoding a single frame.

Also home to the suggestion ranking (R10.3), which is likewise pure arithmetic.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .cpu import budget as cpu_budget
from .model import Caps, JobSpec, MediaInfo, ScanEntry, output_container

# ---------------------------------------------------------------------------
# Quality ladder (R3)
# ---------------------------------------------------------------------------

# mode -> CRF, per codec family. The names are the stable contract; the numbers
# shift with the codec because a CRF is not comparable across codecs (R3.3).
_LADDER = {
    "hevc": {"low": 28, "medium": 24, "high": 20},
    "av1":  {"low": 38, "medium": 32, "high": 26},
    "h264": {"low": 26, "medium": 22, "high": 18},
}

# x265 preset: slower than 'medium' buys real bitrate at a real time cost.
_PRESET = "medium"

# Audio codecs that are legal in each target container (R6.2).
_AUDIO_OK = {
    "mp4": frozenset({"aac", "mp3", "ac3", "eac3", "alac", "opus"}),
    "mkv": frozenset({"aac", "mp3", "ac3", "eac3", "alac", "opus", "vorbis",
                      "flac", "pcm_s16le", "pcm_s24le", "dts", "truehd"}),
}

_TARGET_BPP = {"low": 0.020, "medium": 0.035, "high": 0.055}


class PlanError(ValueError):
    """The requested combination cannot be encoded with the available ffmpeg."""


@dataclass(frozen=True)
class Plan:
    argv: list[str]
    encoder: str
    crf: int | None
    audio_action: str        # 'copy' or 'aac'
    notes: tuple[str, ...]   # user-facing warnings, e.g. the archive growth one
    threads: int = 0         # cores this job is allowed (0 = unset)


# ---------------------------------------------------------------------------
# Encoder selection (R4)
# ---------------------------------------------------------------------------

def choose_encoder(caps: Caps, mode: str, hw: str) -> str:
    """Pick a video encoder. Hardware is opt-in and never required (R4.1, R4.2)."""
    if mode == "archive":
        # Only truly lossless options. FFV1 is the archival standard; x265's
        # lossless mode is a distant second and is not bit-exact in all
        # chroma formats, so it is not offered as a fallback.
        if caps.has("ffv1"):
            return "ffv1"
        raise PlanError("archive mode needs the ffv1 encoder, which this ffmpeg lacks")

    if hw == "auto":
        # VAAPI is checked against a real device node, because ffmpeg lists
        # hevc_nvenc and hevc_qsv on machines that have neither. An encoder
        # appearing in `-encoders` proves nothing about the hardware.
        if caps.has("hevc_vaapi") and caps.vaapi_device is not None:
            return "hevc_vaapi"
        for enc in ("hevc_nvenc", "hevc_qsv", "hevc_videotoolbox", "hevc_amf"):
            if caps.has(enc):
                return enc
        # No usable hardware: fall through to software, silently (R4.1).

    if caps.has("libx265"):
        return "libx265"
    if caps.has("libx264"):
        return "libx264"
    raise PlanError("no usable video encoder found (need libx265 or libx264)")


def _family(encoder: str) -> str:
    if encoder.startswith(("libsvtav1", "libaom")):
        return "av1"
    if encoder.startswith("libx264"):
        return "h264"
    return "hevc"


def resolve_crf(encoder: str, mode: str, override: int | None) -> int | None:
    if mode == "archive":
        return None
    if override is not None:
        return override
    return _LADDER[_family(encoder)][mode]


# ---------------------------------------------------------------------------
# Output naming (R1)
# ---------------------------------------------------------------------------

def target_container(src_ext: str, mode: str = "medium",
                     container: str | None = None) -> str:
    """The container this job will actually produce.

    Archive mode forces MKV: FFV1 is not a legal MP4 codec, so honouring an
    mp4 request there produces an ffmpeg error rather than an archive.
    """
    if mode == "archive":
        return "mkv"
    return (container or output_container(src_ext)).lstrip(".").lower()


def output_path(src: Path, container: str | None = None, *, exists=Path.exists) -> Path:
    """`<stem>_compressed.<ext>` beside the original, never overwriting (R1.1/1.2).

    `exists` is injectable so the collision logic is unit-testable without a
    filesystem.
    """
    ext = (container or output_container(src.suffix.lstrip("."))).lstrip(".")
    stem = src.stem
    candidate = src.with_name(f"{stem}_compressed.{ext}")
    n = 2
    while exists(candidate) or candidate == src:
        candidate = src.with_name(f"{stem}_compressed ({n}).{ext}")
        n += 1
    return candidate


def existing_output(src: Path, container: str | None = None,
                    *, exists=Path.exists) -> Path | None:
    """The `<stem>_compressed.<ext>` sibling, if one is already there.

    Without this, re-running `compress` on a folder quietly produces
    `clip_compressed (2).mp4` next to `clip_compressed.mp4` — the collision
    rule in R1.2 is doing exactly what it should, but the result is a folder
    full of duplicates. Callers skip these unless the user asks again.
    """
    ext = container or output_container(src.suffix.lstrip("."))
    candidate = src.with_name(f"{src.stem}_compressed.{ext.lstrip('.')}")
    return candidate if exists(candidate) else None


def is_compressed_output(path: Path) -> bool:
    """R1.4 — recognise our own output so scan and batch skip it."""
    stem = path.stem
    if stem.endswith("_compressed"):
        return True
    # '..._compressed (2)'
    base, sep, tail = stem.rpartition(" (")
    return bool(sep) and tail.endswith(")") and tail[:-1].isdigit() and base.endswith("_compressed")


# ---------------------------------------------------------------------------
# The build
# ---------------------------------------------------------------------------

def build(spec: JobSpec, caps: Caps, tmp: Path, cores: int | None = None) -> Plan:
    """The whole ffmpeg command line for one job. Pure — touches no disk.

    `cores` is the thread budget; None means "work it out from the machine",
    which leaves RESERVED_CORES free so the desktop stays usable.
    """
    src, mode = spec.src, spec.mode
    threads = cpu_budget(cores)
    encoder = choose_encoder(caps, mode, spec.hw)
    crf = resolve_crf(encoder, mode, spec.crf)
    target_ext = tmp.suffix.lstrip(".").lower()
    notes: list[str] = []

    # -threads caps ffmpeg's own decode/filter pools. It is NOT enough on its
    # own for x265, which runs a private thread pool sized from the machine and
    # ignores it; that needs pools= in -x265-params below.
    argv: list[str] = [str(caps.ffmpeg), "-hide_banner", "-nostdin", "-y",
                       "-threads", str(threads)]

    if encoder == "hevc_vaapi":
        # The device and the hwupload filter must both be present; VAAPI
        # encodes from GPU-side NV12 surfaces, not from the decoded frames.
        argv += ["-vaapi_device", str(caps.vaapi_device)]

    argv += ["-i", str(src.path)]

    if encoder == "hevc_vaapi":
        argv += ["-vf", "format=nv12,hwupload"]

    # -- video ------------------------------------------------------------
    argv += ["-map", "0:v:0", "-c:v", encoder]
    if encoder == "ffv1":
        # level 3 + slicecrc is the archival-safe configuration; slices give
        # us multithreading, the CRC makes corruption detectable.
        argv += ["-level", "3", "-g", "1", "-slices", "16", "-slicecrc", "1",
                 "-threads", str(threads)]
        notes.append(
            "archive mode is mathematically lossless and will usually produce a "
            "LARGER file than the original, because the original is already "
            "lossily compressed (R3.2)"
        )
    elif encoder in ("libx265", "libx264"):
        argv += ["-preset", _PRESET, "-crf", str(crf)]
        argv += ["-threads", str(threads)]          # output-side decoder/encoder
        if encoder == "libx265":
            argv += ["-tag:v", "hvc1"] if target_ext == "mp4" else []
            argv += ["-x265-params", f"log-level=error:pools={threads}"]
    elif encoder.endswith("_vaapi"):
        # VAAPI has no CRF; -qp is the closest constant-quality control.
        argv += ["-qp", str(crf)]
    elif encoder.endswith("_nvenc"):
        argv += ["-preset", "p5", "-rc", "vbr", "-cq", str(crf), "-b:v", "0"]
        if target_ext == "mp4":
            argv += ["-tag:v", "hvc1"]
    elif encoder.endswith(("_qsv", "_amf", "_videotoolbox")):
        argv += ["-global_quality", str(crf)]

    # -- audio (R6) -------------------------------------------------------
    if src.acodec is None:
        audio_action = "none"
        argv += ["-an"]
    elif src.acodec in _AUDIO_OK.get(target_ext, frozenset()):
        audio_action = "copy"
        argv += ["-map", "0:a?", "-c:a", "copy"]
    else:
        audio_action = "aac"
        argv += ["-map", "0:a?", "-c:a", "aac", "-b:a", "192k"]
        notes.append(f"audio re-encoded to AAC: {src.acodec} is not valid in .{target_ext} (R6.2)")

    # -- metadata (R5) ----------------------------------------------------
    # ffmpeg's -autorotate is on by default, so the rotation is baked into the
    # pixels and the output carries no rotate tag. That is correct, and it is
    # why verify.py compares *display* dimensions rather than coded ones.
    argv += ["-map_metadata", "0"]
    if target_ext == "mp4":
        # One -movflags only: ffmpeg takes the last occurrence of an option and
        # silently drops the earlier one, so these must be combined.
        argv += ["-movflags", "+faststart+use_metadata_tags"]

    # -- progress + output ------------------------------------------------
    argv += ["-progress", "pipe:1", "-nostats", "-loglevel", "error", str(tmp)]

    return Plan(argv=argv, encoder=encoder, crf=crf, audio_action=audio_action,
                notes=tuple(notes), threads=threads)


# ---------------------------------------------------------------------------
# Suggestion ranking (R10.3) — pure arithmetic, no ffmpeg
# ---------------------------------------------------------------------------

def estimate(info: MediaInfo, mode: str = "medium") -> tuple[float, int]:
    """Return (bpp, estimated bytes saved). An estimate, always labelled (R10.4).

    The model: a modern encoder at a given quality lands near a known
    bits-per-pixel figure for camera footage. If a file is already at or below
    that figure, there is nothing to win — which is exactly the case for
    already-HEVC phone video, and the reason this ranking exists at all.
    """
    bpp = info.bpp
    target = _TARGET_BPP.get(mode, 0.035)
    if info.vcodec in ("hevc", "av1", "vp9"):
        # Already a modern codec: a re-encode to the same family buys far less,
        # and costs a generation of quality. Only flag the badly over-bitrated.
        target *= 1.6
    if bpp <= 0 or bpp <= target or info.duration <= 0:
        return bpp, 0
    predicted_bits = target * info.pixels_per_second * info.duration
    predicted_bytes = int(predicted_bits / 8)
    # Audio and container overhead survive the re-encode; do not promise them.
    overhead = int(info.size * 0.02)
    saving = info.size - predicted_bytes - overhead
    return bpp, max(0, saving)


# Measured 2026-09-20 on four real phone clips (see docs/qa-checklist.md):
# three files at an identical 0.086 bits/pixel compressed to 77%, 64% and 29%
# of their original size. The model is roughly unbiased but cannot see content
# complexity, so a single number would be a lie dressed as arithmetic. Callers
# that show a figure to a person should show the range and point at `preview`.
ESTIMATE_ERROR = 0.5


def estimate_range(info: MediaInfo, mode: str = "medium") -> tuple[int, int]:
    """(low, high) bytes saved. Wide on purpose — see ESTIMATE_ERROR."""
    saving = estimate(info, mode)[1]
    if saving <= 0:
        return 0, 0
    return (max(0, int(saving * (1 - ESTIMATE_ERROR))),
            min(info.size, int(saving * (1 + ESTIMATE_ERROR))))


def rank(infos: list[MediaInfo], mode: str = "medium") -> list[ScanEntry]:
    """Biggest estimated saving first; rank 0 is 'compress this one first'."""
    entries = []
    for i in infos:
        bpp, saving = estimate(i, mode)
        entries.append(ScanEntry(info=i, bpp=bpp, est_saving=saving))
    entries.sort(key=lambda e: (-e.est_saving, -e.info.size))
    return [ScanEntry(info=e.info, bpp=e.bpp, est_saving=e.est_saving, rank=n)
            for n, e in enumerate(entries)]
