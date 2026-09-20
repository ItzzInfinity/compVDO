# Requirements — numbered product rules

Every rule is testable. The Android implementation is checked against *this*
document, not against the Python source.

## 1. Naming & placement
- **R1.1** Output is `<stem>_compressed<ext>` beside the original.
- **R1.2** If that name exists, append ` (2)`, ` (3)` … never overwrite.
- **R1.3** The output container defaults to `.mp4` for MP4/MOV/3GP/M4V/AVI
  sources and `.mkv` for MKV/WEBM sources. `--container` overrides.
- **R1.4** A file already ending in `_compressed` is skipped by scan and batch
  unless `--include-compressed`.

## 2. Never lose an original
- **R2.1** The original is never touched during encoding. ffmpeg writes to a
  `.compvdo-tmp-<pid>.<ext>` file, renamed into place only on exit code 0.
- **R2.2** Deleting the original requires an explicit flag/toggle **and** a
  passed verification (R8).
- **R2.3** Delete means "move to trash" where a trash exists
  (`gio trash` on Linux, Recycle Bin on Windows, MediaStore trash on Android);
  a hard `unlink` only with `--purge`.
- **R2.4** Cancel or crash leaves the temp file removed and the original intact.

## 3. Quality ladder (`--mode`)
| Mode | Codec | Rate control | Typical result on phone footage |
|---|---|---|---|
| `low` | HEVC | CRF 28 | 75–85 % smaller, soft on detail |
| `medium` *(default)* | HEVC | CRF 24 | 55–70 % smaller, no visible loss in motion |
| `high` | HEVC | CRF 20 | 40–55 % smaller, visually lossless |
| `archive` | FFV1 (MKV) | mathematically lossless | **larger**, bit-exact |

- **R3.1** `medium` is the default when nothing is specified.
- **R3.2** `archive` must print/show an explicit warning that the file will
  usually grow, before it runs.
- **R3.3** CRF numbers are the HEVC ladder. If the chosen encoder is AV1 the
  ladder shifts (`low` 38 / `medium` 32 / `high` 26); the *names* stay stable.
- **R3.4** `--crf` overrides the ladder for a single run.

## 4. Hardware acceleration
- **R4.1** HW encode (VAAPI / NVENC / QSV) is **opportunistic**. Detection
  failure falls back to libx265 silently — never an error.
- **R4.2** HW encode is *not* the default for `high`/`archive`: fixed-function
  encoders are measurably worse per bit. Default is software x265; `--hw auto`
  opts in.
- **R4.3** Encoder capability is probed once and cached (see data model).

## 5. Metadata & orientation
- **R5.1** Rotation/display-matrix is preserved. A portrait phone clip must not
  come out sideways.
- **R5.2** Creation time, GPS and maker tags are copied (`-map_metadata 0`)
  where the target container supports them.
- **R5.3** File mtime is set to the original's mtime after write.

## 6. Audio
- **R6.1** Audio is stream-copied by default (`-c:a copy`).
- **R6.2** If the source audio codec is not legal in the target container, it is
  re-encoded to AAC 192 kbps and that is reported.

## 7. Honesty about results
- **R7.1** Every job reports before size, after size, ratio, and wall time.
- **R7.2** If the output is **not smaller**, the job is flagged `GREW`. The
  output is kept (the user asked for it) but never auto-deletes the original.

## 8. Verification (gate for any delete)
- **R8.1** Output duration is within 0.1 s (or 0.5 %) of the source.
- **R8.2** Output stream count and video dimensions match.
- **R8.3** A full decode pass (`ffmpeg -v error -i out -f null -`) produces no
  errors.
- **R8.4** All three must pass before R2.2 permits a delete. Failure keeps both
  files and reports.

## 9. Batch
- **R9.1** Jobs run sequentially by default (ffmpeg already saturates cores);
  `--jobs N` for parallelism.
- **R9.2** A batch writes a run-state file so `Ctrl-C` then re-run skips what
  already succeeded.
- **R9.3** One file's failure never aborts the batch; it is recorded and the
  batch continues.

## 10. Scan & suggestion
- **R10.1** Scan reports: path, size, duration, resolution, fps, video codec,
  bitrate, mtime.
- **R10.2** Sort by `size` | `date` | `name` | `savings` (the suggestion rank).
- **R10.3** **Suggestion rank** = estimated bytes saved, from bits-per-pixel:
  `bpp = bitrate / (width * height * fps)`. Phone H.264 typically sits at
  0.08–0.15 bpp; HEVC at CRF 24 lands near 0.04. Files far above the target bpp
  rank highest. Already-HEVC, already-low-bpp, and very short clips rank lowest.
- **R10.4** Rank is an *estimate* and is labelled as such — never presented as a
  promise.

## 11. Preview
- **R11.1** "Preview before saving" is implemented as **encode a short segment**
  (default 10 s from 1/3 into the clip) at the chosen settings, then play source
  and sample side by side. Encoding the whole file to then discard it would cost
  the user the entire runtime twice.
- **R11.2** Preview never writes into the source folder; it uses a temp dir.

## 12. UI (GUI front-ends)
- **R12.1** The UI never blocks; encoding runs off the UI thread.
- **R12.2** Cancel terminates the ffmpeg process within 1 s and cleans the temp.
- **R12.3** Progress is per-file percent + overall batch percent, from ffmpeg's
  `-progress` output, not guessed.
- **R12.4** Material 3 look: elevation, rounded 12 dp corners, one accent colour,
  system light/dark following.
