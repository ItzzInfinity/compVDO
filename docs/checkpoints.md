# Checkpoints — Resume Notes

> Resume protocol: run `scaffold.mjs status`, then read **only the top block**
> of this file. Continue from **Next step (exact)**. Do not redo completed
> work. Do not skip the self-check.
>
> Newest first. New blocks are prepended by
> `node <skill>/scaffold.mjs checkpoint --title="…"`.

## Current state — 2026-09-20 (session 2) — Linux core + CLI complete and trialled

- **Current phase:** 1 — Linux core + CLI (complete except the real-clip trial)
- **Last completed task:** 1.17 README
- **Next task:** 2.1 `gui/worker.py` — QThread bridge over encode.py (needs PySide6 installed)

### Session summary
1. Phase 0 in full: FSD.md, requirements (R1–R12), architecture, data model,
   roadmap, QA checklist, manual tasks. User-requested filenames
   (`Architecture.md`, `ToDo.md`, `memory.md`, `manualTasks.md`) are symlinks
   onto the scaffold set, so `status`/`check` still work.
2. Built the whole Linux core: model, probe, plan, encode, verify, trash, scan,
   batch, settings, cli. Stdlib only.
3. 72 tests pass in ~31s. The 49 unit tests need no ffmpeg and run in 0.12s.
4. Trialled live on a mixed folder (mp4 / rotated mp4 / mkv+flac / avi silent /
   a corrupt file): 62% saved, every output verified, corrupt file skipped
   cleanly.

**Gotchas learned this session:**
- **Archive mode proved the spec's central claim.** FFV1 on a 5.0 MB lossy clip
  produced 10.2 MB — 203% — and was bit-exact. This is why `medium` is the
  default and `archive` carries a warning.
- **Do not use the PSNR filter to test bit-exactness.** The two files have
  different timebases, so psnr compares misaligned frames and reports ~25 dB on
  files that are provably identical. Compare decoded frame hashes instead:
  `ffmpeg -v error -i F -map 0:v -pix_fmt yuv420p -f framemd5 - | grep -v '^#' | awk '{print $NF}' | md5sum`.
- **`ffmpeg` keeps only the LAST occurrence of an option.** Two `-movflags`
  flags silently discarded the first. Combine them: `+faststart+use_metadata_tags`.
- **`ffmpeg -encoders` lists hardware encoders the machine cannot use.** This
  box reports `hevc_nvenc` and `hevc_qsv` with no such hardware. `--hw auto`
  therefore checks for a real `/dev/dri` device before trusting VAAPI.
- **`-metadata:s:v:0 rotate=90` does not stick on the encode that creates the
  file** — only on a remux (`-c copy`). Test fixtures must be built in two steps.
- **`-autorotate` is on by default**, so a rotated source comes out with the
  rotation baked into the pixels, coded dimensions swapped, and no rotate tag.
  That is correct behaviour, and it is why `verify.py` compares *display*
  dimensions. Comparing coded dimensions would reject every portrait phone video.
- **ffmpeg takes ~1.2s to exit on SIGTERM** because it finalises the output. On
  cancel we discard that output anyway, so the grace period is 0.25s then
  SIGKILL. Cancel now lands in 0.32s.
- **`gio trash` honours a snap-sandboxed `XDG_DATA_HOME` and reports success.**
  In a VS Code snap terminal, `XDG_DATA_HOME=~/snap/code/263/.local/share`, so
  trashed videos go somewhere the file manager never shows — recoverable in
  theory, lost in practice. `trash.py` detects this and reports the exact
  destination path every time.
- **Re-running `compress` on a folder used to create `_compressed (2)` copies.**
  The R1.2 collision rule was working as designed; the default now skips a
  source that already has a compressed sibling, with `--again` to override.
- **FFV1 is not legal in MP4**, so `archive` forces the MKV container
  regardless of what the source was or what `--container` says.

### Partially done
- Nothing half-built. Phase 1 is closed except 1.16.

### Blocked
- **1.16** — the real-phone-clip trial, on **M1**. Everything is proven against
  ffmpeg-generated clips; what is unproven is the real-world compression ratio
  on actual camera footage, which is the number that matters to the user.

### Next step (exact)
Install PySide6 (`pip install PySide6`), then write `compvdo/gui/worker.py`: a
`QThread`-based bridge over `encode.run()` exposing `progress(int,int,float)`,
`file_done(JobResult)` and `finished(list)` signals, driving a `CancelToken`.
Do not put any encoding logic in the GUI layer — it calls `batch.run_batch()`.

### Assumptions
- Visually-lossless HEVC is the default and true lossless is the opt-in
  `archive` mode. Filed as **M4.1** for the user to overturn if they disagree.
- Output container defaults: MP4 for everything except MKV/WEBM sources.

## Previous state — 2026-09-20 (session 1) — spec written, awaiting approval

- **Current phase:** 0 — planning
- **Last completed task:** docs scaffold created
- **Next task:** fill in the spec and the Phase 0 docs, then hand back for approval

### Session summary
1. Scaffolded the spec + docs set.

**Gotchas learned this session:**
- none yet

### Partially done
- The spec is a skeleton; every `…` is unfilled.

### Blocked
- none

### Next step (exact)
Fill `../FSD.md` from the user's brief, then stop and ask for approval before
writing any code.

### Assumptions
- none yet
