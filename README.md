# compVDO

Shrink videos from your phone, on your own machine. No cloud, no account, no
cost, no telemetry.

Linux CLI is working today. GUI, Windows and Android are on the roadmap.

## The thing worth knowing first

Phone videos are **already compressed** (H.264 or HEVC, straight out of the
camera encoder). Re-encoding one *mathematically losslessly* does not shrink
it — it **inflates** it, because a lossless codec has to reproduce the existing
compression artifacts bit for bit.

Measured on this project's own test clip:

| Mode                                    | Result                                               |
| --------------------------------------- | ---------------------------------------------------- |
| `archive` (true lossless, FFV1)         | 5.0 MB → **10.2 MB** — bit-exact, and twice the size |
| `high` (visually lossless, HEVC CRF 20) | 5.0 MB → 2.4 MB                                      |
| `medium` (default, HEVC CRF 24)         | 5.0 MB → 2.0 MB                                      |

So the default is **visually lossless**: modern codec, high quality, typically
40–70 % smaller with nothing you can see at normal viewing distance. True
lossless is still there as `--mode archive`, for when you want an archival
master and know it will grow.

## Install

```bash
sudo apt install ffmpeg        # or dnf/pacman — ffmpeg is the only requirement
git clone <this repo> && cd compVDO
pip install -e .               # the core is stdlib-only
```

Check what your machine can do:

```bash
compvdo caps
```

## Use

```bash
# What's worth compressing, and roughly how much you'd save
compvdo scan ~/Videos
compvdo scan ~/Videos --sort size          # or date, name, savings

# Compress one file, or a whole folder
compvdo compress clip.mp4
compvdo compress ~/Videos --mode high

# Try the settings on a 10-second sample before committing to a long encode
compvdo preview clip.mp4 --mode high

# Compress and remove the originals — only ever after verification passes
compvdo compress ~/Videos --delete-original

# Check an output against its source yourself
compvdo verify clip.mp4 clip_compressed.mp4

# Full metadata for a folder, plus a per-file verdict, as one markdown document
compvdo report ~/Videos -o report.md
compvdo report ~/Videos --compare ~/Videos/output -o report.md   # before/after
```

Output always lands **beside the original** as `<name>_compressed.<ext>`.

### Modes

| Mode | Typical saving | Use it for |
|---|---|---|
| `low` | 75–85 % | messaging, quick sharing |
| `medium` *(default)* | 55–70 % | general library cleanup |
| `high` | 40–55 % | keepers you don't want to think about again |
| `archive` | **grows the file** | bit-exact masters |

`--crf N` overrides the ladder if you know what you want.

### How much will it actually save?

Less predictably than you would like. Measured on four real phone clips, three
of them recorded at an identical 0.086 bits per pixel came out at **77 %, 64 %
and 29 %** of their original size. Bits-per-pixel arithmetic cannot see how busy
the picture is, so `scan` gives you a *ranking*, not a promise.

Use `compvdo preview <file>` when you want a real number: it encodes a
ten-second sample at your chosen settings, in a temp folder, in well under a
minute.

Also worth knowing: modern phones already record **HEVC**, not H.264. A HEVC
source re-encoded to HEVC gains much less than the H.264 case everyone quotes,
and costs a generation of quality. `scan` accounts for this and ranks such
files lower.

## Safety

These are enforced, not aspirational — each maps to a numbered rule in
`docs/requirements.md` and has a test behind it.

- The original is never touched during encoding. ffmpeg writes to a temp file
  beside the output and it is renamed into place only on success.
- `--delete-original` moves the original to your **system trash**, and only
  after the output passes three checks: duration matches, displayed dimensions
  match, and a full decode pass reports no errors. Any failure keeps both files.
- If the output is not smaller, it is flagged `GREW` and the original is kept
  regardless of what you asked for.
- Cancel (Ctrl-C) stops ffmpeg within a second and removes the temp file.
- Interrupt a batch and re-run it: it picks up where it stopped.
- Portrait videos stay portrait. There is a test for it.

## Hardware acceleration

Off by default. `--hw auto` uses VAAPI/NVENC when real hardware is present and
falls back to software silently when it isn't. It is faster and produces
noticeably larger files at the same quality, which is why it is opt-in.

Note that `ffmpeg -encoders` lists `hevc_nvenc` and `hevc_qsv` on machines that
have neither, so compvdo checks for an actual device before trusting them.

## Project layout

Spec-first. `FSD.md` is the contract, `docs/roadmap.md` (→ `ToDo.md`) is the
only source of truth for what is done, and `docs/checkpoints.md` (→ `memory.md`)
is where each session's findings are recorded.

```bash
python3 -m pytest -q       # 72 tests; the e2e ones generate their own clips
```

## Licence

The code here is yours to do as you like with. ffmpeg is invoked as an external
program and is not bundled on Linux; if you package it for Windows, the GPL
build's terms apply to that distribution.
