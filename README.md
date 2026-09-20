<h1 align="center">compVDO</h1>

<p align="center">
  Shrink the videos on your phone, on your own machine.<br>
  <strong>No cloud · no account · no cost · no telemetry.</strong>
</p>

<p align="center">
  <img src="docs/images/gui-dark.png" alt="compVDO scanning a folder of phone videos" width="900">
</p>

---

## Read this first: “lossless” does not mean what you want it to mean

Your phone already compressed these videos — H.264 or HEVC, straight out of the
camera encoder. Re-encoding one **mathematically losslessly** does not shrink
it. It *inflates* it, because a lossless codec has to reproduce the existing
compression artifacts bit for bit.

Measured on this project's own test clip:

| Mode | Result |
|---|---|
| `archive` — true lossless (FFV1) | 5.0 MB → **10.2 MB** · bit-exact, and twice the size |
| `high` — visually lossless (HEVC CRF 20) | 5.0 MB → 2.4 MB |
| `medium` — default (HEVC CRF 24) | 5.0 MB → 2.0 MB |

So the default is **visually lossless**: a modern codec at high quality, giving
real savings with nothing you can see at normal viewing distance. True lossless
is still available as `--mode archive`, for archival masters, and it warns you
before it runs.

## It works

Four real phone clips, `--mode medium`, software x265:

| Clip | Before | After | Ratio | Time |
|---|---|---|---|---|
| `VID_20230715_162642.mp4` | 263.0 MB | 94.4 MB | **36 %** | 4:45 |
| `video_20260731_224613.mp4` | 341.7 MB | 80.4 MB | **24 %** | 11:10 |
| `video_20260705_064442.mp4` | 249.5 MB | 159.3 MB | **64 %** | 10:28 |
| `video_20260705_062317.mp4` | 231.5 MB | 153.0 MB | **66 %** | 9:39 |

**1.06 GB → 487 MB — 598.5 MB saved (55 %)**, every output verified, every
original untouched.

> **How much will *your* files save? Less predictably than you would like.**
> Three of those clips were recorded at an *identical* 0.086 bits per pixel and
> came out at 66 %, 64 % and 24 %. Bits-per-pixel arithmetic cannot see how busy
> the picture is, so `scan` gives you a **ranking**, not a promise.
> `compvdo preview <file>` encodes a ten-second sample in under a minute and
> tells you the real number — it predicted 39 % where the encode landed at 36 %.

Also worth knowing: **modern phones record HEVC, not H.264.** Every clip above
was already HEVC. The “70 % smaller” figures you read elsewhere assume an H.264
source and do not apply. `scan` accounts for this and ranks such files lower.

---

## Install

```bash
sudo apt install ffmpeg          # the only hard requirement (dnf/pacman also fine)
git clone <this repo> && cd compVDO
pip install -e .                 # the CLI core is stdlib-only
pip install -e ".[gui]"          # add the desktop app
```

Check what your machine can do:

```console
$ compvdo caps
ffmpeg  /usr/bin/ffmpeg  (version 4.4.2)
encoders: ffv1, hevc_nvenc, hevc_qsv, hevc_vaapi, libaom-av1, libx264, libx265
vaapi device: /dev/dri/renderD128
cpu budget  : 10 of 12 cores (2 left for the rest of the system)
```

## Use it — command line

```bash
# What is worth compressing, and roughly what you would save
compvdo scan ~/Videos
compvdo scan ~/Videos --sort size        # or date, name, savings

# Try the settings on a 10-second sample before committing to a long encode
compvdo preview holiday.mp4 --mode high

# Compress one file, or a whole folder
compvdo compress holiday.mp4
compvdo compress ~/Videos --mode high

# Compress and remove the originals — only ever after verification passes
compvdo compress ~/Videos --delete-original

# Everything ffprobe knows, plus a verdict per file, as one markdown document
compvdo report ~/Videos -o report.md
compvdo report ~/Videos --compare ~/Videos/output -o report.md   # before/after
```

Output always lands **beside the original**, named `<name>_compressed.<ext>`.

```console
$ compvdo compress ~/Videos
[INFO] compressing 4 file(s), mode=medium, hw=off, using 10 of 12 cores

  [1/4] OK   VID_20230715_162642.mp4  263.0 MB -> 94.4 MB (36%)  4:45
  [2/4] OK   video_20260731_224613.mp4  341.7 MB -> 80.4 MB (24%)  11:10
  ...
------------------------------------------------------------
  compressed : 4
  saved      : 598.5 MB of 1.1 GB (55%)
------------------------------------------------------------
```

## Use it — desktop

```bash
compvdo-gui
```

<p align="center">
  <img src="docs/images/gui-light.png" alt="compVDO in light theme, showing the archive-mode warning" width="900">
</p>

Pick a folder, sort by whatever you care about, tick what you want, press
Compress. The window follows your system light/dark setting, never blocks while
encoding, and Cancel actually stops ffmpeg — measured at 0.32 s.

## Quality modes

| Mode | Typical saving | Use it for |
|---|---|---|
| `low` | 75–85 % | messaging, quick sharing |
| **`medium`** *(default)* | 55–70 % | general library cleanup |
| `high` | 40–55 % | keepers you do not want to think about again |
| `archive` | **grows the file** | bit-exact masters |

`--crf N` overrides the ladder if you know exactly what you want.

## Your machine stays usable

By default compVDO uses **all but two cores**, so a long batch does not make the
desktop unpleasant. x265 needs both `-threads` *and* its own `pools=` setting to
respect that — setting only the first caps nothing.

```bash
compvdo compress ~/Videos --cores 4     # or pin it explicitly
```

## Safety

Enforced, not aspirational. Each maps to a numbered rule in
[`docs/requirements.md`](docs/requirements.md) and has a test behind it.

- **The original is never touched while encoding.** ffmpeg writes to a temp file
  beside the output, renamed into place only on success.
- **`--delete-original` moves the original to your system trash**, and only after
  three checks pass: duration matches, displayed dimensions match, and a full
  decode reports no errors. Any failure keeps both files. The GUI names every
  file it is about to delete and defaults the dialog to Cancel.
- **If the output is not smaller it is flagged `GREW`** and the original is kept
  regardless of what you asked for.
- **Cancel stops ffmpeg within a second** and removes the temp file.
- **Interrupt a batch and re-run it** — it picks up where it stopped.
- **Portrait videos stay portrait.** There is a test for it.

## Project layout

Spec-first. `FSD.md` is the contract; `docs/roadmap.md` is the only source of
truth for what is done; `docs/checkpoints.md` records each session's findings.

| Document | What it is |
|---|---|
| [`FSD.md`](FSD.md) | the spec: purpose, stack with rejected alternatives, acceptance criteria |
| [`docs/requirements.md`](docs/requirements.md) | numbered, testable product rules R1–R13 |
| [`docs/architecture.md`](docs/architecture.md) | module map, the flow, and why each decision went the way it did |
| [`docs/data-model.md`](docs/data-model.md) | the five dataclasses and the two files on disk |
| [`docs/roadmap.md`](docs/roadmap.md) | status, one line per task |
| [`docs/checkpoints.md`](docs/checkpoints.md) | resume notes and every gotcha found so far |
| [`docs/qa-checklist.md`](docs/qa-checklist.md) | manual and automated test checklist, with measured results |
| [`manual-task.md`](manual-task.md) | things only you can do |

```
compvdo/
  probe.py    ffprobe → MediaInfo; encoder capability detection
  plan.py     quality ladder → ffmpeg argv (pure), and the savings ranking
  encode.py   temp-file discipline, real progress, a cancel that works
  verify.py   the three checks that gate deleting an original
  batch.py    resumable queue, per-file isolation
  cpu.py      how many cores we are allowed
  report.py   full-metadata markdown reports
  cli.py      the command line
  gui/        PySide6 desktop app
```

```bash
python3 -m pytest -q      # 124 tests; the fast ones need no ffmpeg at all
```

## Status

| Platform | State |
|---|---|
| **Linux CLI** | ✅ done, trialled on real phone footage |
| **Linux GUI** | ✅ done, trialled |
| **Android** | ⬜ next — Compose + Media3 `Transformer` |
| **Windows** | ⬜ after that — same core, PyInstaller |

Android is a **re-implementation of the rules**, not a port of this code:
ffmpeg-kit was retired in January 2025, so it uses Media3 `Transformer`
instead. That means no `archive` mode there — there is no FFV1.

## Licence

The code here is yours to do as you like with. ffmpeg is invoked as an external
program and is not bundled on Linux; if you package it for Windows, the GPL
build's terms apply to that distribution.
