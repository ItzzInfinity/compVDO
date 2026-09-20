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

## Android device trial — 2026-09-20 (M3)

First run on real hardware, 303 videos / 34.54 GB across 16 folders.

**Confirmed working:**
- Scanner reaches everything, including `Download` — the fix for the
  `MediaStore.Video.Media` view problem holds on device.
- **Cancel stops the running file**, and the log shows `no unfinished output
  left behind` — the `NonCancellable` cleanup does run, so no orphaned
  `IS_PENDING` row.
- A real encode: `446.3 MB → 113.4 MB (25%)`, verified, and the original
  correctly offered for removal.
- `POST_NOTIFICATIONS` requested and granted at batch start.
- Preview opens in an external player.

**Three defects found and fixed the same day:**
- [x] **A video in `Download` failed before encoding**: *"Primary directory
      Download not allowed … allowed directories are [DCIM, Movies, Pictures]"*.
      R1.1 ("beside the original") is not achievable on Android for those roots;
      output now falls back to `Movies/compVDO` and the job says so.
- [x] **Opening a file in an external player threw** *"UID 10333 does not have
      permission to content://media/…"*. Cause: `FLAG_GRANT_READ_URI_PERMISSION`
      on a MediaStore URI we hold by *permission*, not by grant — there is no
      grant to forward, and the error names our own uid, which reads like the
      file being unreadable to us. The flag is now only added for SAF URIs.
- [x] **The library was rescanned four times in three seconds** and again during
      compression, because Home's `LaunchedEffect` re-runs on every re-entry.
      Now `ensureScanned()`; Refresh remains explicit.

**Still to check on device after these fixes:**
- [ ] A `Download` video now compresses, and the note naming `Movies/compVDO` appears
- [ ] The compressed output opens in an external player
- [ ] Home no longer rescans on tab switches
- [ ] The Log tab's Save button writes `Download/compvdo-log-<timestamp>.txt`

## Android code validation — 2026-09-20

Build: `./gradlew assembleDebug` **succeeds** (JDK 17, compileSdk 35, minSdk 26,
Media3 1.5.1) → `app/build/outputs/apk/debug/app-debug.apk`, 21 MB.
~2,150 lines of Kotlin across 24 files. **Zero tests** (no `src/test`, no
`src/androidTest`).

Threading was checked and is correct: `viewModelScope.launch` runs on
`Dispatchers.Main.immediate`, so `Transformer` is built, started and polled on
the main looper, which is what Media3 requires.

### 🔴 Blocking — data loss

- [ ] **R2.3 is violated: "trash" is a permanent delete.** `BatchRunner.trashOriginal()`
      carries the comment *"Uses MediaStore.createTrashRequest on API 30+"* and then
      calls `contentResolver.delete(uri, null, null)`. `createTrashRequest` appears
      nowhere in the tree. The original is destroyed, not trashed, and nothing can
      recover it. On the desktop this same rule is what sends files to a recoverable
      trash and reports the exact path.
- [ ] **The security exception that asks the user for permission is swallowed.**
      On API 30+, deleting media the app does not own raises
      `RecoverableSecurityException`, whose `IntentSender` is how Android prompts
      the user. `catch (_: Exception) { false }` discards it, so the delete either
      destroys the file or silently reports "not deleted" — never the documented
      behaviour.
- [ ] **No confirmation before deleting originals.** dev_guide.md §12 requires a
      dialog naming the targets, stating the count, and defaulting to the safe
      button. The setting is a bare toggle in `SettingsScreen`. This is the same
      gap that §12 caught on the desktop GUI.
- [ ] **R8.3 does not verify playability, but gates the delete as though it does.**
      `Verifier` reads the track *format header* and then sets `playable = true`
      with the comment "if we got this far with no exception, it's playable".
      A truncated file has a valid header. The desktop runs a full decode pass.
      A weak gate plus a permanent delete is how footage gets lost.

### 🟠 Significant

- [ ] **Cancel does not stop the current file (R12.2).** `CompressViewModel.cancel()`
      sets a plain `Boolean`, which `BatchRunner` only checks *between* files.
      Nothing cancels the running `Transformer`. The `invokeOnCancellation {
      transformer.cancel() }` hook exists but never fires, because the coroutine is
      never cancelled. Pressing Cancel during a ten-minute encode does nothing for
      ten minutes. Desktop stops in 0.32 s.
- [ ] **Output opened `"w"`, but MP4 muxing needs to seek** back to write the `moov`
      atom. `openFileDescriptor(outputUri, "w")` should be `"rw"`. Likely a runtime
      export failure on device — the first thing to watch for in the trial.
- [ ] **The foreground notification never updates.** `CompressionService.updateProgress()`
      is never called from anywhere; the service is unbound, so the ViewModel cannot
      reach the instance. The notification stays at "Compressing… 0%" for the whole
      batch. (dev_guide.md §17: a method that exists and is never invoked.)
- [ ] **`POST_NOTIFICATIONS` is declared but never requested.** Only
      `READ_MEDIA_VIDEO` is requested in `HomeScreen`. On API 33+ the foreground
      notification will not appear.

### 🟡 Divergences from the shared spec

- [ ] **R10.3 ranking is not the same arithmetic**, though `BitsPerPixel` says
      "Same formula as the Python implementation". `compute()` matches; `rank()`
      does not — Python sorts by estimated bytes saved, Kotlin buckets into 0–4 by
      threshold. Either align it or correct the comment.
- [ ] **`fps` defaults to 30.0 when unknown.** The desktop deliberately returns
      bpp 0 rather than invent a figure (R10.4). Assuming 30 for 60 fps footage
      doubles the apparent bpp and over-promises the saving — and three of the four
      trial clips were 60 fps.
- [ ] **R9.2 resume is not implemented.** No equivalent of `.compvdo-run.json`;
      an interrupted batch restarts from the beginning. Not claimed in the
      docstring, so this is a gap rather than a false claim.
- [ ] **R11 preview is absent entirely.** No preview code anywhere. This was an
      explicit item in the original brief for Android.
- [ ] **MediaStore work runs on the main thread** (`createOutputUri` queries and
      inserts, `getFileSize` opens a descriptor). ANR risk on a large library.
- [ ] Dead code in `TransformerEngine.runTransformer`: a `getProgress()` call whose
      result is discarded, and an empty `Transformer.Listener` added for nothing.
      dev_guide.md §17 names the discarded-probe shape specifically.
- [ ] `extractor.release()` is not in a `finally`, so it leaks on the exception path.
- [ ] `nameExists()` queries `VOLUME_EXTERNAL` while the insert targets
      `VOLUME_EXTERNAL_PRIMARY`. Harmless today (the query is a superset).

### ✅ Correct and worth keeping

- `ARCHIVE` mode is correctly **absent** from `CompressionMode`, with the reason
  documented — matches the architecture decision (no FFV1 on MediaCodec).
- R1.1/R1.2 output naming and collision handling via MediaStore are right,
  including `IS_PENDING` and cleanup of the entry on failure.
- R2.1 holds: the original is never opened for writing.
- R7.2 holds: `grew` is detected and **blocks the delete** independently of
  verification.
- R9.3 holds: a per-file `try/catch` means one failure cannot abort the batch.
- The verifier's sorted-dimension comparison is a sound adaptation of the
  displayed-vs-coded rule.
- Dependency choices are current and sensible; the stack matches the recorded
  decision.

## Manual — Android: what the 2026-09-20 changes need checked on hardware

None of this could be verified without a device. Each line is a specific thing
to look at, not a general "test the app".

**Deleting originals (the fixes to R2.3/R8.3/R8.4):**
- [ ] On Android 11+: confirming a delete shows the **system** trash dialog, and
      the files are afterwards restorable from the gallery's Trash
- [ ] On Android 9 (the new minSdk): the dialog says "Delete permanently" and
      **not** "move to trash" — there is no media trash before API 30
- [ ] A batch where one output GREW: that original is not offered for deletion
- [ ] Declining the prompt leaves every original in place

**Export (the `"w"` → `"rw"` fix):**
- [ ] Exports actually complete. If they fail, this is the first suspect —
      the MP4 muxer seeks back to write `moov` at the end

**Scanner coverage:**
- [ ] A video downloaded by Chrome appears — separately on Android 12 (expected
      to work via `READ_EXTERNAL_STORAGE`) and Android 13+ (expected to need a
      SAF grant on `Download`; confirm whether it does)
- [ ] On Android 9: check logcat for `MediaScanner: full projection failed` —
      if the lean-retry path kicks in, duration/width/height/bucket are lost
- [ ] No `volume '…' not scannable` + `IllegalArgumentException` in logcat
      (MediaProvider runs a strict grammar check on the `LIKE ?` selection)
- [ ] WhatsApp Video folder appears with the right name and count
- [ ] `adb shell ls -a` on `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/`
      — a `.nomedia` there means MediaStore cannot see it at all and SAF is mandatory
- [ ] A document-MIME row can actually be opened by Media3 `Transformer`; it may
      throw `SecurityException` on 33+ even though the row listed
- [ ] SD card / OTG: per-volume iteration finds its videos, and one failing
      volume degrades instead of emptying the list
- [ ] Folders do not split into two tiles where `BUCKET_ID` is null

**Log:**
- [ ] Copy puts the log on the clipboard; Send opens a share sheet with the text

## Manual — Android (needs M3)
- [ ] **Do NOT enable "Delete originals" until the trash bug above is fixed.**
- [ ] Export actually succeeds on device (watch the `"w"` vs `"rw"` issue first)
- [ ] Output appears in the gallery next to the original (R1.1)
- [ ] Encode survives the screen locking
- [ ] Cancel actually stops the Transformer job
- [ ] `archive` mode is absent from the UI, not just disabled
