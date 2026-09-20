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

## Manual — Linux (needs M1)
- [ ] Portrait phone clip stays portrait in VLC **and** mpv (R5.1)
- [ ] Creation time and GPS survive (`exiftool` before/after) (R5.2)
- [ ] mtime matches the original (R5.3)
- [ ] Audio stream is byte-identical when copied (R6.1)
- [ ] Cancel mid-encode: process gone within 1 s, temp file gone, original intact (R2.4, R12.2)
- [ ] `--delete-original` on a clip that fails verification → both files survive (R8.4)
- [ ] Deleted original is recoverable from the trash (R2.3)
- [ ] `--mode archive` is bit-exact: `ffmpeg -i src -i dst -filter_complex psnr -f null -` → `inf`
- [ ] `--mode archive` printed the growth warning *before* running (R3.2)
- [ ] A batch of 20 mixed clips: Ctrl-C at #7, re-run resumes at #7 (R9.2)
- [ ] One corrupt file in a batch does not stop the other 19 (R9.3)

### Measured results — fill in during the trial
| Clip | Source codec | Size before | Mode | Size after | Ratio | Time | Visible loss? |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

## Manual — GUI
- [ ] Window stays responsive during a 4K encode (R12.1)
- [ ] Per-file and overall progress both reach 100 % and match reality (R12.3)
- [ ] Sort by size / date / name / savings all work and are stable
- [ ] Preview encodes a sample only, in temp, not in the source folder (R11.2)
- [ ] Dark mode follows the system and is readable (R12.4)
- [ ] A `GREW` result is visibly flagged (R7.2)

## Manual — Windows (needs M2)
- [ ] Runs on a machine with no Python and no ffmpeg installed
- [ ] Non-ASCII filenames and paths over 260 chars
- [ ] Delete goes to the Recycle Bin

## Manual — Android (needs M3)
- [ ] Output appears in the gallery next to the original (R1.1)
- [ ] Encode survives the screen locking
- [ ] Cancel actually stops the Transformer job
- [ ] `archive` mode is absent from the UI, not just disabled
