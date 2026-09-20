# QA checklist

Run before calling a phase done. Every line maps to a rule in
`requirements.md`. Record results, including the ugly ones.

## Automated (`python3 -m pytest -q`)
- [ ] `plan.build()` matrix: 4 modes × 6 containers × rotation 0/90 → expected argv
- [ ] Ladder shifts correctly when the encoder is AV1 (R3.3)
- [ ] `--crf` overrides the ladder (R3.4)
- [ ] Output naming collision produces ` (2)` and never overwrites (R1.2)
- [ ] `_compressed` files are excluded from scan (R1.4)
- [ ] bpp / est_saving arithmetic on known inputs (R10.3)
- [ ] verify rejects a truncated file (R8.1, R8.3)
- [ ] batch resume skips entries in `.compvdo-run.json` (R9.2)
- [ ] settings round-trip preserves unknown keys

## Manual — Linux (M1 supplied 2026-09-20)
- [x] Portrait phone clip stays portrait — verified 2026-09-20 on two real 1080×1920 clips; displayed size unchanged, probe-level check. *Not yet eyeballed in VLC/mpv by a human.*
- [ ] Creation time and GPS survive (`exiftool` before/after) (R5.2)
- [x] mtime matches the original — verified 2026-09-20; all four outputs kept the source mtime
- [ ] Audio stream is byte-identical when copied (R6.1)
- [x] Cancel mid-encode — verified 2026-09-20 from both CLI (0.32s) and the GUI Cancel button (0.32s); no temp, no stray ffmpeg, original intact
- [ ] `--delete-original` on a clip that fails verification → both files survive (R8.4)
- [ ] Deleted original is recoverable from the trash (R2.3)
- [x] `--mode archive` is bit-exact — verified 2026-09-20 by comparing decoded
      frame hashes, which is the reliable test:
      `ffmpeg -v error -i F -map 0:v -pix_fmt yuv420p -f framemd5 - | grep -v '^#' | awk '{print $NF}' | md5sum`
      must match for source and output. **Do not use the PSNR filter for this:**
      the two files have different timebases, so psnr compares misaligned frames
      and reports ~25 dB on files that are provably identical.
- [x] `--mode archive` warns before running — verified 2026-09-20 in CLI, and the GUI shows both a banner and a confirm dialog
- [ ] A batch of 20 mixed clips: Ctrl-C at #7, re-run resumes at #7 (R9.2)
- [x] A corrupt file does not stop the batch — verified 2026-09-20; a 2-byte .mp4 was reported and skipped while the rest ran

### Measured results — real phone footage, 2026-09-20 (M1)

Four clips supplied by the user in `testVideos/`, `--mode medium`, software
x265, 12 threads. Outputs verified (duration, displayed size, full decode) and
moved to `testVideos/output/`.

| Clip | Source | Before | After | Ratio | Time | Est. said |
|---|---|---|---|---|---|---|
| `VID_20230715_162642.mp4` (1080×1920, 30fps, 15.4 Mb/s) | HEVC | 263.0 MB | 94.4 MB | **36%** | 4:45 | 24% |
| `video_20260731_224613.mp4` (1080×1920, 10.7 Mb/s) | HEVC | 341.7 MB | 80.4 MB | **24%** | 11:10 | 66% |
| `video_20260705_064442.mp4` (1920×1080, 60fps, 10.7 Mb/s) | HEVC | 249.5 MB | 159.3 MB | **64%** | 10:28 | 66% |
| `video_20260705_062317.mp4` (1920×1080, 60fps, 10.7 Mb/s) | HEVC | 231.5 MB | 153.0 MB | **66%** | 9:39 | 66% |

**Total 1.06 GB → 487.2 MB, 598.5 MB saved (55%).** Full report, including every
ffprobe field, is at `testVideos/output/REPORT.md`.

What this trial taught us:

1. **Every clip was already HEVC.** Modern phones do not record H.264. The
   "70% smaller" figures quoted for H.264 sources do not apply, and the
   ranking had to be made more lenient for already-modern codecs.
2. **The savings estimate has large per-file error.** Three clips at an
   *identical* 0.086 bits/pixel came out at 66%, 64% and 24%. Bits-per-pixel
   cannot see content complexity. The estimate is now presented as a range and
   documented as a ranking signal, not a prediction. See R10.4.
3. **`preview` is accurate.** It predicted 39% for the first clip, which
   finished at 36%. A 10-second sample costs under a minute and is the honest
   way to answer "what will this save".
4. **Throughput:** roughly 0.3–0.5× real time for 1080p on 12 cores at
   `-preset medium`. 13 minutes of footage took 36 minutes.
5. Both portrait clips (rotation 90°) came out displayed 1080×1920, coded
   1080×1920, rotation tag cleared — correct, and the reason verification
   compares displayed rather than coded dimensions.

## Manual — GUI
- [x] Window stays responsive during an encode — verified 2026-09-20; 27 progress events delivered while encoding, UI thread never blocked
- [x] Per-file and overall progress reach 100% — verified 2026-09-20; overall bar monotonic, final 1000/1000
- [ ] Sort by size / date / name / savings all work and are stable
- [ ] Preview encodes a sample only, in temp, not in the source folder (R11.2)
- [ ] Dark mode follows the system and is readable (R12.4)
- [x] A `GREW` result is visibly flagged — verified 2026-09-20; shown as “GREW — kept original” and the original is never deleted

## Manual — Windows (needs M2)
- [ ] Runs on a machine with no Python and no ffmpeg installed
- [ ] Non-ASCII filenames and paths over 260 chars
- [ ] Delete goes to the Recycle Bin

## Manual — Android (needs M3)
- [ ] Output appears in the gallery next to the original (R1.1)
- [ ] Encode survives the screen locking
- [ ] Cancel actually stops the Transformer job
- [ ] `archive` mode is absent from the UI, not just disabled
