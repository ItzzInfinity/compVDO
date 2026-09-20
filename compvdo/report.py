"""Full-metadata reports (`compvdo report`).

Two audiences in one document: a summary anyone can act on, and the complete
ffprobe dump for when you need to know exactly what a camera wrote. Nothing is
filtered out of the dump — the point of it is that nothing is hidden.
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from .model import Caps, MediaInfo
from .plan import estimate, estimate_range, target_container
from .probe import ProbeError, info as probe_info

# Tags worth pulling to the top of a summary: where the file came from.
_INTERESTING_TAGS = (
    "creation_time", "com.android.version", "com.android.manufacturer",
    "com.android.model", "com.android.capture.fps", "location",
    "location-eng", "make", "model", "encoder", "handler_name",
    "major_brand", "compatible_brands", "rotate", "language", "title",
    "artist", "date", "software", "comment",
)


def raw_probe(path: Path, caps: Caps) -> dict:
    """Everything ffprobe knows, including chapters and per-stream side data."""
    proc = subprocess.run(
        [str(caps.ffprobe), "-v", "error", "-print_format", "json",
         "-show_format", "-show_streams", "-show_chapters",
         "-show_programs", "-show_private_data", str(path)],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise ProbeError(proc.stderr.strip().splitlines()[-1] if proc.stderr.strip()
                         else "ffprobe failed")
    return json.loads(proc.stdout)


def _human(n: float | None) -> str:
    if n is None:
        return "—"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:,.0f} {unit}" if unit == "B" else f"{n:,.2f} {unit}"
        n /= 1024
    return f"{n} B"


def _clock(seconds: float) -> str:
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def _table(rows: list[tuple[str, str]]) -> list[str]:
    if not rows:
        return ["_none_", ""]
    out = ["| Field | Value |", "|---|---|"]
    out += [f"| {k} | {str(v).replace('|', chr(92) + '|')} |" for k, v in rows]
    out.append("")
    return out


def _flatten(prefix: str, value, into: list[tuple[str, str]]) -> None:
    """Flatten nested ffprobe JSON into dotted key/value rows.

    ffprobe nests differently across versions (side_data_list, tags, disposition),
    so flattening generically is more durable than naming every field.
    """
    if isinstance(value, dict):
        for k, v in value.items():
            _flatten(f"{prefix}.{k}" if prefix else k, v, into)
    elif isinstance(value, list):
        for n, v in enumerate(value):
            _flatten(f"{prefix}[{n}]", v, into)
    else:
        text = str(value)
        if "\n" in text:                # display matrices arrive multi-line
            text = " ".join(text.split())
        into.append((f"`{prefix}`", text))


def _codec_long(streams: list[dict]) -> str:
    video = next((s for s in streams if s.get("codec_type") == "video"), {})
    parts = [video.get("codec_long_name", "")]
    if profile := video.get("profile"):
        parts.append(f"profile {profile}")
    if level := video.get("level"):
        parts.append(f"level {level}")
    if pix := video.get("pix_fmt"):
        parts.append(pix)
    return ", ".join(p for p in parts if p) or "unknown"


def _verdict(info: MediaInfo, saving: int, mode: str) -> str:
    """The one line a person actually reads."""
    if saving <= 0:
        if info.vcodec in ("hevc", "av1", "vp9"):
            return (f"**Leave it alone.** Already {info.vcodec.upper()} at "
                    f"{info.bpp:.3f} bits/pixel, which is close to what a "
                    f"re-encode would produce. You would spend the time and a "
                    f"generation of quality for very little.")
        return (f"**Little to gain.** {info.bpp:.3f} bits/pixel is already lean "
                f"for {info.vcodec.upper()}.")
    low, high = estimate_range(info, mode)
    pct = saving / info.size * 100
    lead = "**Worth compressing.**" if pct > 25 else "**Marginal.**"
    extra = ""
    if info.vcodec in ("hevc", "av1", "vp9"):
        extra = (f" It is already {info.vcodec.upper()}, so this is a re-encode "
                 f"of an already-modern codec — the gain depends heavily on how "
                 f"busy the footage is.")
    return (f"{lead} At `--mode {mode}`, somewhere between {_human(low)} and "
            f"{_human(high)} saved ({pct:.0f}% central estimate).{extra} "
            f"Run `compvdo preview \"{info.path.name}\"` for a real number.")


def build_markdown(paths: list[Path], caps: Caps, *, mode: str = "medium",
                   title: str = "Video metadata report") -> str:
    lines: list[str] = [
        f"# {title}", "",
        f"Generated {datetime.now(timezone.utc).astimezone():%Y-%m-%d %H:%M %Z} "
        f"by `compvdo report` using ffprobe {caps.ffmpeg_version}.", "",
        "Every field ffprobe reports is included verbatim further down; the "
        "summary tables are derived from those same fields.", "",
    ]

    good: list[tuple[Path, MediaInfo, dict, int]] = []
    bad: list[tuple[Path, str]] = []
    for p in paths:
        try:
            info = probe_info(p, caps)
            good.append((p, info, raw_probe(p, caps), estimate(info, mode)[1]))
        except (ProbeError, OSError) as e:
            bad.append((p, str(e)))

    # --- library overview -------------------------------------------------
    lines += ["## At a glance", ""]
    if good:
        lines += ["| File | Size | Length | Resolution | Codec | Bitrate | bits/px | Est. saving |",
                  "|---|---|---|---|---|---|---|---|"]
        for p, i, _, saving in good:
            lines.append(
                f"| `{p.name}` | {_human(i.size)} | {_clock(i.duration)} | "
                f"{i.display_width}×{i.display_height} | {i.vcodec.upper()} | "
                f"{i.vbitrate / 1e6:.1f} Mb/s | {i.bpp:.3f} | "
                f"{_human(saving) if saving else '—'} |")
        total = sum(i.size for _, i, _, _ in good)
        saved = sum(s for *_, s in good)
        lines += ["", f"**{len(good)} file(s), {_human(total)} total.** Estimated saving at "
                      f"`--mode {mode}`: **{_human(saved)}** "
                      f"({saved / total * 100:.0f}%).", "",
                  "> These estimates come from bits-per-pixel arithmetic and are "
                  "deliberately rough. Measured on real phone footage, three "
                  "clips with an identical 0.086 bits/pixel compressed to 77%, "
                  "64% and 29% of their original size — bits-per-pixel cannot "
                  "see how busy the picture is. Use the figures to decide "
                  "*which* files to do first, and `compvdo preview <file>` to "
                  "find out what one will actually save.", ""]
    if bad:
        lines += ["Unreadable:", ""]
        lines += [f"- `{p.name}` — {why}" for p, why in bad]
        lines.append("")

    # --- per file ---------------------------------------------------------
    for p, i, raw, saving in good:
        fmt = raw.get("format", {})
        streams = raw.get("streams", [])
        lines += ["---", "", f"## `{p.name}`", "", _verdict(i, saving, mode), ""]

        lines += ["### Summary", ""]
        lines += _table([
            ("Path", f"`{p}`"),
            ("Size", f"{_human(i.size)} ({i.size:,} bytes)"),
            ("Duration", f"{_clock(i.duration)} ({i.duration:.3f} s)"),
            ("Container", f"{fmt.get('format_long_name', '?')} (`{fmt.get('format_name', '?')}`)"),
            ("Video codec", f"{i.vcodec} — {_codec_long(streams)}"),
            ("Coded size", f"{i.width}×{i.height}"),
            ("Displayed size", f"{i.display_width}×{i.display_height}"),
            ("Rotation", f"{i.rotation}°" + (" (portrait)" if i.rotation in (90, 270) else "")),
            ("Frame rate", f"{i.fps:.3f} fps"),
            ("Overall bitrate", f"{i.vbitrate / 1e6:.2f} Mb/s"),
            ("Bits per pixel", f"{i.bpp:.4f}"),
            ("Audio codec", i.acodec or "none"),
            ("Streams", str(len(streams))),
            ("Modified", datetime.fromtimestamp(i.mtime).strftime("%Y-%m-%d %H:%M:%S")),
            ("Would become", f"`{p.stem}_compressed.{target_container(i.container, mode)}`"),
        ])

        interesting = [(f"`{k}`", v) for k, v in (fmt.get("tags") or {}).items()
                       if k.lower() in _INTERESTING_TAGS or k.lower().startswith("com.")]
        if interesting:
            lines += ["### Where it came from (container tags)", ""] + _table(interesting)

        lines += ["### Streams", ""]
        for n, st in enumerate(streams):
            kind = st.get("codec_type", "?")
            lines += [f"#### Stream {n} — {kind}", ""]
            rows: list[tuple[str, str]] = []
            _flatten("", st, rows)
            lines += _table(rows)

        if raw.get("chapters"):
            lines += ["### Chapters", ""]
            rows = []
            _flatten("", raw["chapters"], rows)
            lines += _table(rows)

        lines += ["### Container (format)", ""]
        rows = []
        _flatten("", fmt, rows)
        lines += _table(rows)

        lines += ["<details><summary>Complete ffprobe JSON</summary>", "",
                  "```json", json.dumps(raw, indent=2, ensure_ascii=False), "```",
                  "", "</details>", ""]

    return "\n".join(lines) + "\n"
