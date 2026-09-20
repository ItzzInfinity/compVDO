"""The command-line front-end. The only module in the core allowed to print."""

from __future__ import annotations

import argparse
import shutil
import signal
import sys
import tempfile
from dataclasses import replace
from pathlib import Path

from . import __version__
from .cpu import RESERVED_CORES, describe as describe_cores
from .plan import PRESETS, PRESET_DEFAULT
from .batch import plan_jobs, prepare, run_batch
from .encode import CancelToken
from .model import JobResult, JobSpec, MODES
from .plan import (AUDIO_CHOICES, AUDIO_DEFAULT, PlanError, build, output_path,
                   resolve_audio)
from .probe import FFmpegMissing, ProbeError, info as probe_info
from .scan import SORTS, scan, sort_entries
from .settings import cached_caps, load, save
from .verify import check

EXIT_OK, EXIT_FAILURES, EXIT_NO_FFMPEG, EXIT_USAGE = 0, 1, 2, 3


# --- formatting -----------------------------------------------------------

def human(n: float | None) -> str:
    if n is None:
        return "-"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:,.0f} {unit}" if unit == "B" else f"{n:,.1f} {unit}"
        n /= 1024
    return f"{n} B"


def clock(seconds: float) -> str:
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def _bar(fraction: float, width: int = 24) -> str:
    filled = int(fraction * width)
    return "#" * filled + "-" * (width - filled)


class Reporter:
    """Progress on one rewritten line; results as a table (R7.1)."""

    def __init__(self, quiet: bool = False) -> None:
        self.quiet = quiet
        self.tty = sys.stdout.isatty()

    def progress(self, idx, total, spec, fraction, secs) -> None:
        if self.quiet:
            return
        name = spec.src.path.name
        if len(name) > 32:
            name = name[:29] + "..."
        line = f"  [{idx}/{total}] {name:<32} [{_bar(fraction)}] {fraction*100:5.1f}%"
        end = "\r" if self.tty else "\n"
        if self.tty or fraction >= 1.0:
            print(line, end=end, flush=True)

    def done(self, idx, total, result: JobResult) -> None:
        if self.quiet:
            return
        if self.tty:
            print(" " * shutil.get_terminal_size().columns, end="\r")
        mark = {"ok": "OK  ", "grew": "GREW", "failed": "FAIL",
                "cancelled": "STOP", "skipped": "SKIP"}[result.status]
        ratio = f"{result.ratio*100:.0f}%" if result.ratio else "-"
        print(f"  [{idx}/{total}] {mark} {result.spec.src.path.name}  "
              f"{human(result.spec.src.size)} -> {human(result.dst_size)} ({ratio})  "
              f"{clock(result.seconds)}")
        if result.message:
            print(f"        {result.message}")


def summarise(results: list[JobResult]) -> int:
    """Print the closing table. Returns the process exit code."""
    ok = [r for r in results if r.status == "ok"]
    grew = [r for r in results if r.status == "grew"]
    failed = [r for r in results if r.status == "failed"]
    cancelled = [r for r in results if r.status == "cancelled"]
    skipped = [r for r in results if r.status == "skipped"]

    saved = sum(r.saved or 0 for r in ok)
    before = sum(r.spec.src.size for r in ok)

    print("\n" + "-" * 60)
    print(f"  compressed : {len(ok)}")
    if grew:
        print(f"  grew       : {len(grew)}  (kept; originals never deleted — R7.2)")
    if skipped:
        print(f"  skipped    : {len(skipped)}  (done in an earlier run)")
    if cancelled:
        print(f"  cancelled  : {len(cancelled)}")
    if failed:
        print(f"  failed     : {len(failed)}")
        for r in failed:
            print(f"      {r.spec.src.path.name}: {r.message.splitlines()[0] if r.message else '?'}")
    if ok:
        print(f"  saved      : {human(saved)} of {human(before)} "
              f"({saved / before * 100:.0f}%)" if before else "")
    deleted = sum(1 for r in results if r.deleted)
    if deleted:
        print(f"  originals deleted: {deleted}")
    print("-" * 60)
    return EXIT_FAILURES if failed else EXIT_OK


# --- commands -------------------------------------------------------------

def cmd_caps(args) -> int:
    caps = cached_caps(force=args.refresh)
    print(f"ffmpeg  {caps.ffmpeg}  (version {caps.ffmpeg_version})")
    print(f"ffprobe {caps.ffprobe}")
    print(f"encoders: {', '.join(sorted(caps.encoders)) or 'none of interest'}")
    print(f"vaapi device: {caps.vaapi_device or 'none'}")
    print(f"cpu budget  : {describe_cores(None)}")
    if caps.vaapi_device is None and any(e.endswith(("_nvenc", "_qsv")) for e in caps.encoders):
        print("note: ffmpeg lists hardware encoders it cannot necessarily use;\n"
              "      `--hw auto` verifies before trusting them and falls back to software.")
    return EXIT_OK


def cmd_scan(args) -> int:
    caps = cached_caps()
    entries, skipped = scan(Path(args.path), caps, mode=args.mode,
                            recursive=not args.no_recursive,
                            include_compressed=args.include_compressed)
    if not entries:
        print("No videos found.")
        return EXIT_OK

    entries = sort_entries(entries, args.sort)
    print(f"{'#':>3}  {'name':<34} {'size':>10} {'dur':>7} {'codec':>6} "
          f"{'bpp':>6} {'est. saving':>12}")
    print("-" * 86)
    for e in entries:
        i = e.info
        print(f"{e.rank:>3}  {i.path.name[:34]:<34} {human(i.size):>10} "
              f"{clock(i.duration):>7} {i.vcodec:>6} {e.bpp:>6.3f} "
              f"{human(e.est_saving) if e.est_saving else '-':>12}")
    worth = [e for e in entries if e.est_saving > 0]
    print(f"\n{len(worth)} of {len(entries)} look worth compressing at "
          f"mode={args.mode}; estimated saving "
          f"{human(sum(e.est_saving for e in worth))}.")
    print("These are ROUGH estimates from bits-per-pixel. Measured on real phone\n"
          "footage, three clips at an identical 0.086 bits/pixel came out at 77%,\n"
          "64% and 29% of their original size — the arithmetic cannot see how busy\n"
          "the picture is. Use `compvdo preview <file>` for a real number.")
    for p, why in skipped:
        print(f"  skipped {p.name}: {why}")
    return EXIT_OK


def _collect(args, caps) -> tuple[list, list[tuple[Path, str]]]:
    infos, bad = [], []
    for raw in args.paths:
        p = Path(raw)
        if p.is_dir():
            entries, skipped = scan(p, caps, mode=args.mode,
                                    recursive=not args.no_recursive,
                                    include_compressed=args.include_compressed)
            infos += [e.info for e in entries]
            bad += skipped
        else:
            try:
                infos.append(probe_info(p, caps))
            except (ProbeError, OSError) as e:
                bad.append((p, str(e)))
    return infos, bad


def cmd_compress(args) -> int:
    caps = cached_caps()
    infos, bad = _collect(args, caps)
    for p, why in bad:
        print(f"skipping {p.name}: {why}")
    if not infos:
        print("Nothing to do.")
        return EXIT_OK

    specs, already = plan_jobs(infos, mode=args.mode, hw=args.hw,
                               container=args.container,
                               delete_original=args.delete_original,
                               again=args.again)
    for src, prev in already:
        print(f"skipping {src.name}: {prev.name} already exists (use --again to redo)")
    if not specs:
        print("\nNothing left to do.")
        return EXIT_OK
    audio = getattr(args, "audio", AUDIO_DEFAULT)
    if args.crf is not None or audio != AUDIO_DEFAULT:
        specs = [replace(s, crf=args.crf if args.crf is not None else s.crf,
                         audio=audio) for s in specs]

    # The clamp is announced once, up front, not buried in a per-file note.
    kbps, audio_note = resolve_audio(audio)
    if audio_note:
        print(f"[WARN] {audio_note}")
    if kbps is not None:
        print(f"[INFO] audio will be re-encoded to AAC {kbps} kbps (R6.3)")

    # R3.2 — the archive warning goes out before anything runs, not after.
    if args.mode == "archive":
        print("\n  WARNING: archive mode is mathematically lossless.\n"
              "  Your source is already lossily compressed, so the output will\n"
              "  almost certainly be LARGER than the original — often several\n"
              "  times larger. Use --mode high for 'smaller, no visible loss'.\n")
        if not args.yes and sys.stdin.isatty():
            if input("  Continue? [y/N] ").strip().lower() not in ("y", "yes"):
                return EXIT_OK

    if args.delete_original:
        print(f"  Originals will be {'PURGED' if args.purge else 'moved to the trash'} "
              "only after the output passes verification.\n")

    folder = Path(args.paths[0])
    folder = folder if folder.is_dir() else folder.parent
    if (n := prepare(folder)):
        print(f"  cleared {n} temp file(s) from an interrupted run")

    cancel = CancelToken()
    signal.signal(signal.SIGINT, lambda *_: (
        print("\n  stopping after the current file...", flush=True), cancel.cancel()))

    rep = Reporter(quiet=args.quiet)
    print(f"[INFO] compressing {len(specs)} file(s), mode={args.mode}, "
          f"hw={args.hw}, audio={audio}, using {describe_cores(args.cores)}\n")
    results = run_batch(specs, caps, on_progress=rep.progress, on_done=rep.done,
                        cancel=cancel, resume_in=folder if args.resume else None,
                        purge=args.purge, deep_verify=not args.fast_verify,
                        cores=args.cores, preset=args.preset)
    return summarise(results)


def cmd_preview(args) -> int:
    """R11 — encode a short sample at the chosen settings, in a temp dir."""
    caps = cached_caps()
    src = probe_info(Path(args.path), caps)
    start = max(0.0, src.duration / 3)
    length = min(args.seconds, max(1.0, src.duration - start))

    tmpdir = Path(tempfile.mkdtemp(prefix="compvdo-preview-"))
    sample = tmpdir / f"sample{Path(args.path).suffix}"
    # Cut first, then encode the cut, so the preview costs seconds, not the
    # whole runtime (R11.1).
    import subprocess
    subprocess.run([str(caps.ffmpeg), "-hide_banner", "-nostdin", "-v", "error", "-y",
                    "-ss", f"{start:.2f}", "-t", f"{length:.2f}", "-i", str(src.path),
                    "-c", "copy", str(sample)], check=True)

    cut = probe_info(sample, caps)
    from .plan import target_container
    out = tmpdir / f"sample_compressed.{target_container(cut.container, args.mode)}"
    spec = JobSpec(src=cut, dst=out, mode=args.mode, crf=args.crf, hw=args.hw,
                   audio=getattr(args, "audio", AUDIO_DEFAULT))
    from .encode import run as encode_run
    rep = Reporter(quiet=args.quiet)
    outcome = encode_run(spec, caps, cores=args.cores, preset=args.preset,
                         on_progress=lambda f, s: rep.progress(1, 1, spec, f, s))
    print()
    if not outcome.ok:
        print("preview failed:\n" + outcome.stderr_tail)
        return EXIT_FAILURES

    ratio = out.stat().st_size / cut.size
    full = int(src.size * ratio)
    print(f"  sample     : {length:.0f}s from {clock(start)} into the clip")
    print(f"  sample size: {human(cut.size)} -> {human(out.stat().st_size)} ({ratio*100:.0f}%)")
    print(f"  projected  : {human(src.size)} -> {human(full)} for the whole file (estimate)")
    print(f"\n  original sample : {sample}\n  compressed      : {out}")
    print(f"\n  Compare them, e.g.:\n    mpv {sample} {out}")
    print(f"\n  Nothing was written next to your original. Delete {tmpdir} when done.")
    return EXIT_OK


def cmd_report(args) -> int:
    """Full metadata for a file or folder, as one markdown document."""
    from .report import build_markdown
    from .scan import find_videos

    caps = cached_caps()
    root = Path(args.path)
    paths = find_videos(root, recursive=not args.no_recursive,
                        include_compressed=args.include_compressed)
    if not paths:
        print("No videos found.")
        return EXIT_OK

    print(f"Reading metadata from {len(paths)} file(s)...")
    compare_dirs = [Path(d) for d in (args.compare or [])]
    text = build_markdown(
        paths, caps, mode=args.mode,
        title=args.title or f"Video metadata report — {root.name or root}",
        compare_dirs=compare_dirs or None)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(f"Wrote {out}  ({len(text):,} characters)")
    return EXIT_OK


def cmd_verify(args) -> int:
    caps = cached_caps()
    src = probe_info(Path(args.original), caps)
    verdict = check(src, Path(args.output), caps)
    print("PASS" if verdict else "FAIL")
    for r in verdict.reasons:
        print(f"  {r}")
    return EXIT_OK if verdict else EXIT_FAILURES


def cmd_config(args) -> int:
    data = load()
    if args.set:
        section, _, rest = args.set.partition(".")
        key, _, value = rest.partition("=")
        if section not in data or not key:
            print("use --set defaults.mode=high  (sections: defaults, ui)")
            return EXIT_USAGE
        data[section][key] = {"true": True, "false": False, "null": None}.get(value, value)
        save(data)
    import json
    print(json.dumps({k: v for k, v in data.items() if k != "caps"}, indent=2))
    return EXIT_OK


# --- argument parsing -----------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="compvdo",
        description="Shrink phone videos locally. No cloud, no account, no cost.",
        epilog="Note: 'lossless' re-encoding makes already-compressed phone video "
               "BIGGER. The default mode is visually lossless. See --mode.",
    )
    p.add_argument("--version", action="version", version=f"compvdo {__version__}")
    sub = p.add_subparsers(dest="command", required=True)

    def common(sp):
        sp.add_argument("-m", "--mode", choices=MODES, default="medium",
                        help="quality ladder (default: medium)")
        sp.add_argument("--crf", type=int, help="override the ladder's rate factor")
        sp.add_argument("--hw", choices=("off", "auto"), default="off",
                        help="opt in to hardware encoding (faster, bigger files)")
        sp.add_argument("--audio", choices=AUDIO_CHOICES, default=AUDIO_DEFAULT,
                        metavar="{" + ",".join(AUDIO_CHOICES) + "}",
                        help="re-encode audio to AAC at this bitrate "
                             "(default: keep, which stream-copies it). "
                             "Never goes below 128k.")
        sp.add_argument("--preset", choices=PRESETS, default=PRESET_DEFAULT,
                        help=f"x265 speed/size trade-off (default: {PRESET_DEFAULT}). "
                             f"'fast' measured ~1.4x quicker at the same size")
        sp.add_argument("--cores", type=int, metavar="N",
                        help=f"cores to use (default: all but "
                             f"{RESERVED_CORES}, so the machine stays usable)")
        sp.add_argument("-q", "--quiet", action="store_true")
        return sp

    s = sub.add_parser("scan", help="list videos with a compression suggestion")
    s.add_argument("path")
    s.add_argument("--sort", choices=SORTS, default="savings")
    s.add_argument("--no-recursive", action="store_true")
    s.add_argument("--include-compressed", action="store_true")
    s.add_argument("-m", "--mode", choices=MODES, default="medium")
    s.set_defaults(func=cmd_scan)

    c = common(sub.add_parser("compress", help="compress files or whole folders"))
    c.add_argument("paths", nargs="+")
    c.add_argument("--delete-original", action="store_true",
                   help="trash the original, but only after verification passes")
    c.add_argument("--purge", action="store_true",
                   help="with --delete-original: delete outright instead of trashing")
    c.add_argument("--container", choices=("mp4", "mkv"))
    c.add_argument("--no-recursive", action="store_true")
    c.add_argument("--include-compressed", action="store_true")
    c.add_argument("--no-resume", dest="resume", action="store_false",
                   help="ignore and overwrite any earlier run state")
    c.add_argument("--fast-verify", action="store_true",
                   help="skip the full decode pass (not allowed to gate a delete)")
    c.add_argument("--again", action="store_true",
                   help="re-compress even if a _compressed file already exists")
    c.add_argument("-y", "--yes", action="store_true", help="assume yes to prompts")
    c.set_defaults(func=cmd_compress)

    v = common(sub.add_parser("preview", help="encode a short sample to judge the settings"))
    v.add_argument("path")
    v.add_argument("--seconds", type=float, default=10.0)
    v.set_defaults(func=cmd_preview)

    k = sub.add_parser("verify", help="check an output against its original")
    k.add_argument("original")
    k.add_argument("output")
    k.set_defaults(func=cmd_verify)

    r = sub.add_parser("report", help="dump full metadata for a file or folder to markdown")
    r.add_argument("path")
    r.add_argument("-o", "--output", default="report.md")
    r.add_argument("--title")
    r.add_argument("--compare", action="append", metavar="DIR",
                   help="folder holding the _compressed outputs; adds before/after "
                        "measurements. Repeatable.")
    r.add_argument("-m", "--mode", choices=MODES, default="medium",
                   help="the mode the savings estimates assume")
    r.add_argument("--no-recursive", action="store_true")
    r.add_argument("--include-compressed", action="store_true")
    r.set_defaults(func=cmd_report)

    a = sub.add_parser("caps", help="show what this machine's ffmpeg can do")
    a.add_argument("--refresh", action="store_true")
    a.set_defaults(func=cmd_caps)

    g = sub.add_parser("config", help="show or change stored defaults")
    g.add_argument("--set", metavar="SECTION.KEY=VALUE")
    g.set_defaults(func=cmd_config)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except FFmpegMissing as e:
        print(f"\n{e}\n", file=sys.stderr)
        return EXIT_NO_FFMPEG
    except (PlanError, ProbeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_FAILURES
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return EXIT_FAILURES


if __name__ == "__main__":
    sys.exit(main())
