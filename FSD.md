# compVDO — Local Video Re-compression Tool (Linux · Windows · Android)

> Standard tier. `docs/` holds the detail; this file is the contract.
> Status lives in `docs/roadmap.md` (aliased as `ToDo.md`), never in code.

## Purpose
Shrink videos captured on phones, on the user's own machine, with no cloud, no
account, no cost, and no perceptible loss of quality — from a CLI first, then a
GUI, on Linux first, then Windows, then Android.

## Core goal
One shared compression engine (scan → plan → encode → verify → replace/keep),
wrapped in three front-ends. The engine is the product; the GUIs are skins.

## Hard constraints
- **Free and offline.** No paid codecs, no network calls, no telemetry, no cloud.
- **Never destroy an original** unless the user explicitly asked, and only after
  the output has been verified playable and complete.
- Output naming: `<original stem>_compressed.<ext>` in the **same folder** as the
  original.
- Common containers in: MP4, MOV, MKV, AVI, 3GP, WEBM, M4V, MTS.
- Linux ships and is trialled **before** any Windows or Android work begins.

## Out of scope (v1)
Editing, trimming, filters, subtitles burn-in, colour grading, streaming,
multi-machine queues, any server component, any paid or patent-encumbered
distribution of codec binaries.

---

## ⚠️ The word "lossless" — read this before approving

Phone videos are **already lossy** (H.264 or HEVC out of the camera encoder).
Re-encoding them *mathematically losslessly* (FFV1, or x265 `lossless=1`) does
not shrink them — it **inflates** them, typically 5–30×, because a lossless
codec must reproduce the existing compression artifacts bit-for-bit. A 100 MB
phone clip becomes ~1–3 GB. That is the opposite of the goal.

What actually delivers "smaller file, no visible difference" is a **visually
lossless** re-encode: modern codec (HEVC/AV1), high-quality rate factor, which
on typical phone footage gives **40–70 % size reduction** with no perceptible
difference at normal viewing distance.

**Decision taken (assumption — override it and I will change the default):**
`visually lossless` is the default mode. True mathematical lossless stays
available as an explicit `archive` mode, labelled in the UI with the warning
that it will usually make the file bigger. See `docs/requirements.md` §3 for the
quality ladder.

---

## Stack

| Layer | Choice | Rejected alternative & why |
|---|---|---|
| Encoder | **FFmpeg** (libx265 / VAAPI / NVENC), invoked as a subprocess | *PyAV / libavcodec bindings* — binds us to one ffmpeg ABI, breaks on distro upgrades, and gives no benefit since we need whole-file transcode, not frame access. |
| Core | **Python 3.10+**, stdlib only (`subprocess`, `json`, `pathlib`, `shutil`) | *Rust/Go* — faster to ship nothing in; the work is 99 % waiting on ffmpeg, so host language speed is irrelevant. *ffmpeg-python* — an unmaintained thin wrapper; we parse `-progress` ourselves in ~40 lines. |
| CLI | `argparse` | *Click/Typer* — a dependency for a 6-flag CLI. |
| Desktop GUI | **PySide6** (Qt 6, LGPL, free) with a Material-flavoured QSS theme | *GTK4/libadwaita* — best-in-class on Linux, poor Windows story, and we need one GUI for both. *Electron* — 200 MB runtime for a progress bar. *Tkinter* — no Material, no modern widgets. |
| Windows | Same Python core, **PyInstaller** + bundled `ffmpeg.exe` (gpl static build) | *WSL* — not a shippable product for end users. |
| Android | **Separate** Kotlin app, Jetpack Compose + Material 3, **Media3 `Transformer`** for encoding | *ffmpeg-kit* — **retired Jan 2025**, no Play-compliant 16 KB-page builds, GPL/LGPL packaging risk. *Sharing the Python core* — Chaquopy + an ffmpeg .so is a licensing and APK-size trap. Android re-implements the same rules, not the same code. |

**Consequence of the Android choice:** Android is a parallel implementation of
the *product rules*, not a port of the code. The rules live in
`docs/requirements.md` precisely so both implementations can be checked against
one document.

## Product rules
Numbered and testable — see `docs/requirements.md`. Summary:
R1 output naming · R2 never lose an original · R3 quality ladder ·
R4 hardware acceleration is opportunistic, never required · R5 metadata and
rotation are preserved · R6 audio is copied, never re-encoded by default ·
R7 a job that grows the file is reported, not silently kept · R8 verification
before any delete · R9 batch is resumable · R10 suggestion ranking.

## Phases
1. **Linux core + CLI** — the engine, headless, scriptable. ← *top priority*
2. **Linux GUI** — PySide6 front-end over the same engine.
3. **Windows** — package the same thing.
4. **Android** — Compose app implementing the same rules on Media3.

## Acceptance criteria (v1, Linux)
- `compvdo scan ~/Videos` lists clips with size, date, codec, and a suggestion
  rank, sortable by size/date/name.
- `compvdo compress clip.mp4` produces `clip_compressed.mp4` in the same folder,
  smaller, same duration ±0.1 s, same rotation, playable in VLC and mpv.
- `--delete-original` removes the source **only** after R8 verification passes.
- A batch of 20 mixed-format clips completes, survives a `Ctrl-C` and resumes,
  and prints a per-file before/after table.
- `--mode archive` is genuinely bit-exact (`ffmpeg -i a -i b -filter_complex
  psnr` reports `inf`), and warns about growth.
- The GUI never blocks; cancel actually stops ffmpeg.

## Docs map
`docs/requirements.md` · `docs/architecture.md` (→ `Architecture.md`) ·
`docs/data-model.md` · `docs/roadmap.md` (→ `ToDo.md`) ·
`docs/checkpoints.md` (→ `memory.md`) · `docs/qa-checklist.md` ·
`manual-task.md` (→ `manualTasks.md`)

---

## Original brief (verbatim, as supplied)

> # Lossless Video Compression GUI Windows, Linux, Android
>
> ## Need to research and make a tool on how to compress videos (captured from mobile) in lossless format.
> - Trial on Linux
>   - Using CLI
>   - Using GUI
> - Trial on Windows
> - Trial on Android
>   - Minimalistic GUI
>   - after compression, save them to same name with suffix "_compressed" in the same folder as the original video.
>   - with option to delete the original video after compression.
>   - Material design UI
>   - Support for common video formats (MP4, AVI, MOV, MKV, etc.)
>   - Support for batch processing of multiple videos at once.
>   - Option to choose compression level (e.g., low, medium, high) for balancing quality and file size.
>   - Option to preview the compressed video before saving.
>   - Option to sort by size, date, or name for easier management of videos. and suggest which to compress
>
> ## Note
> - The product should be free
> - No cloud
> - Everything on local machines
> - Check the tokens and execute tasks which can be done independently
> - after doing so stop and check for token exhaustion if not then automatically start next task.
>
> ## Build
> - `Architecture.md` - it will decide what to use, how the flow will look like and everthing else
> - `memory.md` - One stop checkpoint, before creating or touching any file go through this file and DONT dewll the whole repo, update after finished coding or any step
> - `manualTasks.md` - Which user needs to take care of and user will place here their issues and new suggestions
> - `ToDo.md` - update here each time the workflow, which needs to be done and which is done
