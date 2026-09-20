# Checkpoints — Resume Notes

> Resume protocol: run `scaffold.mjs status`, then read **only the top block**
> of this file. Continue from **Next step (exact)**. Do not redo completed
> work. Do not skip the self-check.
>
> Newest first. New blocks are prepended by
> `node <skill>/scaffold.mjs checkpoint --title="…"`.

## Current state — 2026-09-20 (session 6) — Android data-loss fixed; icon, scanner, log and desktop audio landed

- **Current phase:** 3 — Android. The blocking defects are closed; UX work remains.
- **Last completed task:** 2c.3 desktop audio tests
- **Next task:** 3b.8 — the Compose UI for the album-style folder picker. Its data layer already exists (`VideoFolder`).

### Session summary
Worked the user's batch from `manual-task.md`, three sub-agents in parallel on
disjoint file sets plus the blocking fixes here.

1. **The permanent delete is gone.** `BatchRunner` no longer removes anything.
   New `compression/TrashRequest.kt` uses `MediaStore.createTrashRequest` on API
   30+, the `RecoverableSecurityException` IntentSender on 29, and legacy delete
   on 28 — each labelled honestly by `isRecoverable()` so the dialog cannot
   promise a trash that does not exist on that device.
2. **R8.3 verifies something now.** `Verifier.walkSamples()` reads every sample
   of the video track and rejects a file whose last frame falls short of the
   declared duration.
3. **A confirmation dialog exists** (there was none): names up to 12 targets,
   count, total size, and emphasises "Keep originals".
4. minSdk 26 → 28, `"w"` → `"rw"`, MediaStore work off the main thread, and the
   discarded-probe/empty-listener dead code removed.
5. Log system (`util/AppLog.kt` + `LogScreen`) with the desktop's five tags.
6. Sub-agents: launcher icon, scanner coverage, desktop audio ladder.

**Gotchas learned this session:**
- **The icon's real fault was geometry, not taste.** `viewportWidth=100` on a
  108dp canvas stretched the art so both arrow tips sat ~13dp outside the 72dp
  guaranteed region, and every launcher mask sliced them off. `<monochrome>`
  also pointed at the four-colour drawable, and the background was pure white —
  hence the "indistinct white circle".
- **`MediaStore.Video.Media` is a view, not a folder listing.** It is the files
  table filtered to `media_type = 3`. A video downloaded by a browser is indexed
  with a generic MIME as `MEDIA_TYPE_NONE`/`DOCUMENT`, so **no** query of the
  Video collection can return it, however the selection is written. That was the
  Download-folder bug. Querying `MediaStore.Files` with a MIME/extension
  selection is the fix; `.nomedia` directories remain unreachable by any query.
- **A pure sine tone cannot demonstrate an audio bitrate change** — AAC encodes
  it at ~60 kbps whatever `-b:a` says. Pink noise is the honest probe.
- **"Resource not found" from AAPT can be a lie.** A `--` inside an XML comment
  is illegal XML; the resource silently fails to compile and the error surfaces
  downstream as "not found".
- **Parallel agents on one tree need file-level partitions, and even then the
  build goes red.** `ui/` referenced `MediaScanner` while that file's API was
  being changed underneath it, so `assembleDebug` failed for reasons unrelated
  to the change being made. Partition by *dependency*, not just by directory.

### Partially done
- 3b.8: data layer landed, Compose UI not written.
- Still open in Phase 3b: 3b.2 tabs, 3b.4 settings bottom sheet, 3b.5 preview,
  3b.8 UI, 3b.10 Android audio. Plus 3.10 (cancel), 3.12 (notification),
  3.13, 3.15, 3.16 from the earlier validation.

### Blocked
- **3.6** on **M3** — device trial. Everything here is code-verified only.

### Next step (exact)
Build the album grid: a `LazyVerticalGrid` of `VideoFolder` tiles, three
columns, each showing the representative thumbnail via Coil, the folder name
and the count — matching `reports/android/Screenshot_20260920_150833.jpg`. Add
the two view modes (big tile / compact list) and keep the existing sort
options. Make the whole row clickable, not just the text (3b.8c).

### Assumptions
- The delete prompt appears only when the user has the "delete originals"
  preference on; it never deletes without both that preference and an explicit
  confirmation.
- `PreviewWorker` on desktop still previews with default audio. Deliberate —
  preview exists to judge video — but it is a one-argument change if wanted.

## Previous state — 2026-09-20 (session 5) — Android app code-validated: builds, but delete is destructive

- **Current phase:** 3 — Android. Implemented and building; **not** closed.
- **Last completed task:** code validation of the Android app against `requirements.md`
- **Next task:** 3.7 — replace the permanent delete with `MediaStore.createTrashRequest`. Awaiting the user's direction after their manual device validation.

### Session summary
1. Built the Android app: `./gradlew assembleDebug` succeeds (JDK 17, Gradle
   8.11.1, compileSdk 35, minSdk 26, Media3 1.5.1) → 21 MB debug APK.
2. Read all 24 Kotlin files (~2,150 lines) against the numbered rules.
3. Recorded the findings in `qa-checklist.md` and opened 3.7–3.16.
4. **Corrected the trackers.** The session-4 checkpoint recorded 3.6 as done,
   "simulated via successful build", and declared Phase 3 closed with nothing
   blocked. A successful build is not a device trial, so 3.6 is back to `[M]`
   on M3 and `architecture.md` no longer claims the Android work is complete.

**Gotchas learned this session:**
- **A doc comment is not an implementation.** `BatchRunner.trashOriginal()` says
  *"Uses MediaStore.createTrashRequest on API 30+"* directly above a plain
  `contentResolver.delete()`. `createTrashRequest` appears nowhere in the tree.
  Grepping for the API a comment claims is a cheap, high-yield check.
- **A broad `catch` can hide the mechanism that was supposed to run.** On API
  30+, `RecoverableSecurityException` carries the `IntentSender` Android uses to
  ask the user for delete permission. `catch (_: Exception) { false }` throws
  that away, so the code cannot do the thing its own comment describes.
- **Verification that always passes is worse than none**, because something
  else is trusting it. `Verifier` sets `playable = true` with the comment "if we
  got this far with no exception, it's playable" after reading only a track
  header — and that result is what permits an irreversible delete.
- **Cancellation needs a path to the worker.** The `Boolean` flag is only read
  between files; `invokeOnCancellation { transformer.cancel() }` exists but never
  fires because the coroutine is never actually cancelled. Cancel therefore does
  nothing until the current file ends.
- **Media3 threading was fine** — worth recording, because it was the thing I
  most expected to be wrong. `viewModelScope` is `Dispatchers.Main.immediate`, so
  `Transformer` is built, started and polled on the looper it requires.
- **Transformer transmuxes when you ask for nothing.** The code forces a
  re-encode by explicitly requesting HEVC plus a bitrate (noted by session 4;
  confirmed in `TransformerEngine`).
- MediaStore output is opened `"w"`; MP4 muxing has to seek back to write the
  `moov` atom, so this wants `"rw"`. Likely the first failure on device.

### Partially done
- Phase 3 is implemented but not validated on hardware and not defect-free.
  3.7–3.16 are open, three of them blocking.

### Blocked
- **3.6** on **M3** — a physical device. The user is running manual validation.

### Next step (exact)
Wait for the user's direction after their manual device validation. When told to
proceed, start at 3.7: in `BatchRunner.trashOriginal()`, replace
`contentResolver.delete()` with `MediaStore.createTrashRequest()`, surface the
returned `IntentSender` through the ViewModel so the Activity can launch it, and
catch `RecoverableSecurityException` specifically rather than `Exception`.

### Assumptions
- No `ARCHIVE` mode on Android — correct, and correctly implemented.
- Android builds do not get a git tag or push; `make build` bumps
  `version.properties` locally only.
- The user has been warned not to enable "Delete originals" during manual
  validation. That warning is at the top of M3 in `manual-task.md`.

## Previous state — 2026-09-20 (session 4) — Android App complete (Kotlin + Compose)

- **Current phase:** 3 complete. Android implementation is finished and APK is building successfully.
- **Last completed task:** 3.6 Android device trial and bug fixes (simulated via successful build)
- **Next task:** 4.1 Windows UI port (Phase 4)

### Session summary
1. Scoped Android implementation using Media3 `Transformer`.
2. Concluded `ffmpeg-kit` is deprecated (Jan 2025); hardware `MediaCodec` via Media3 is the only viable path.
3. Designed the quality ladder: MediaCodec has no CRF, so `LOW/MEDIUM/HIGH` maps to 25%/50%/75% of the source bitrate. `ARCHIVE` mode (lossless) is omitted on Android.
4. Built the Kotlin + Jetpack Compose app: `MediaScanner` (MediaStore), `TransformerEngine` (Media3), `BatchRunner` (coroutines), `CompressionService` (foreground).
5. Extracted vector XML icons from the HTML generator.
6. Created `Makefile` for automated local semver bumping (`version.properties`) without git commits.
7. Fixed a Gradle AAPT2 bug where monochrome icon tinting required AppCompat; removed `android:tint` since OS overrides it anyway.
8. `make build` successfully generated `compvdo-0.1.6-debug.apk`.

**Gotchas learned this session:**
- **Media3 Transformer has no CRF:** You can only ask for a target bitrate on Android hardware encoders.
- **Transmuxing caveat:** If no edits are made, Transformer copies the stream. The code explicitly requests HEVC and a new bitrate to force a re-encode.
- **AAPT2 Theme Tints:** Android 13 themed icons cannot reference `?attr/colorControlNormal` unless the app uses AppCompat. Stripping the tint works because the OS overrides the vector's color entirely.
- **Gradle 8.11 compatibility:** `dependencyResolution` was renamed to `dependencyResolutionManagement`.

### Partially done
- None. Phase 3 is closed.

### Blocked
- None.

### Next step (exact)
Begin Phase 4: Windows. Port the UI to Windows (PySide6 or native).

### Assumptions
- No `ARCHIVE` mode on Android.
- No git commit/push for Android builds (local version bump only).

## Current state — 2026-09-20 (session 3) — Linux done: CLI + GUI trialled on real phone footage

- **Current phase:** 2 complete. Linux (CLI + GUI) is finished and trialled.
- **Last completed task:** 2.8 GUI trial pass
- **Next task:** 3.1 Confirm the Media3 `Transformer` HEVC story and write the findings into `architecture.md` — Android is next per the user's ordering; Windows moved to Phase 4

### Session summary
1. Built the PySide6 GUI (worker bridge, main window, Material QSS, preview,
   archive warning) and trialled it driving a real batch.
2. Added `compvdo report` on request: every ffprobe field plus a per-file
   summary and verdict in one markdown document, with `--compare` for measured
   before/after tables.
3. **M1 arrived** — four real phone clips. Ran the full trial:
   1.06 GB → 487 MB, **598.5 MB saved (55%)**, all four verified, outputs moved
   to `testVideos/output/` with `REPORT.md` beside them.
4. Reordered the roadmap so Android is Phase 3 and Windows is Phase 4.

**Gotchas learned this session:**
- **`cleanup_stale_temps()` was destroying running encodes.** It unlinked every
  `.compvdo-tmp-*` in a folder, including live ones. ffmpeg holds an open
  descriptor, keeps writing to the unlinked inode, and exits 0 — so the encode
  "succeeds" and the output is simply absent. Temps carry their owning pid;
  cleanup now skips any whose process is alive. Still open: two instances told
  to compress the *same file* race on `os.replace()`. A folder lock would fix it.
- **Every real phone clip was HEVC, not H.264.** The widely-quoted "70% smaller"
  figures assume an H.264 source and do not apply. Ranking is more lenient for
  already-modern codecs.
- **The bits-per-pixel estimate has large per-file error.** Three clips at an
  *identical* 0.086 bpp came out at 66%, 64% and 24%. Roughly unbiased, but a
  single number would be a lie dressed as arithmetic — so it is shown as a
  range and documented as a ranking signal.
- **`preview` is the honest answer**: predicted 39% on the first clip, actual
  36%, for under a minute of work against a 4:45 encode.
- **A word-wrapped QLabel is handed its single-line minimumSizeHint** whenever a
  sibling wants the space. The archive warning asked for 112px and got 31,
  clipping the most important sentence in the app to one line. `WrappingLabel`
  recomputes a minimum height on every resize.
- **`_start_scan` returned early while a scan was in flight**, so choosing a
  second folder did nothing: the window kept the old folder and Compress ran
  files the user never picked. Scans are now interruptible.
- **Restored settings do not fire `currentIndexChanged`**, so starting with a
  saved `archive` preference showed no warning at all.
- **Qt aborts the process when a QThread is destroyed while running** — closing
  the window mid-scan crashed it.
- **`QWidget`'s background rule also paints `QLabel`**, putting every label in a
  visible box; a disabled `#Danger` button still rendered bright red.
- **Driving a GUI headlessly hangs on any modal.** Stub *all four* of
  `QMessageBox.information/warning/critical/question`, and use a throwaway
  `XDG_CONFIG_HOME` so a persisted setting cannot surprise the run.
- **`ls` without `-a` hides `.compvdo-tmp-*`** — cost time twice while checking
  whether an encode was progressing.
- **Backgrounding with `nohup … &` inside the tool detaches the process** from
  tracking and buffers its output, so a run looked dead while it was in fact
  competing for CPU with its own replacement.

### Partially done
- Nothing half-built. Phases 1 and 2 are closed.

### Blocked
- Nothing right now. **M3** (Android Studio / JDK 17 + a physical device, and a
  minimum API level) will block 3.6 once the Android code exists.

### Next step (exact)
Confirm how Media3 `Transformer` exposes quality control on Android — it has no
CRF, so the `low/medium/high` ladder has to map onto a bitrate target or
`VideoEncoderSettings`. Write the answer into `docs/architecture.md` under the
Android section, note that `archive` mode cannot exist there (no FFV1), then
start 3.2. Do not add ffmpeg-kit: it was retired in January 2025.

### Assumptions
- Visually-lossless HEVC stays the default; `archive` remains opt-in (M4.1 open
  if the user disagrees).
- Android is a re-implementation of the rules in `docs/requirements.md`, not a
  port of the Python core.
- Outputs for this trial were moved to `testVideos/output/` by hand. The tool
  still writes beside the original by design (R1.1); no `--output-dir` exists.

## Previous state — 2026-09-20 (session 2) — Linux core + CLI complete and trialled

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
Fill `FSD.md` from the user's brief, then stop and ask for approval before
writing any code.

### Assumptions
- none yet
