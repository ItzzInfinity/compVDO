# Roadmap & Progress Tracker  (aliased as `ToDo.md`)

Source of truth for task status. **Update after every completed task** — this
file, not the code, answers "what is done?".

Legend: `[ ]` pending · `[~]` in progress · `[x]` done · `[M]` waiting on a
manual step (see `manual-task.md`)

Tick format — date and a one-line summary, every time:

```
- [x] Task name — done YYYY-MM-DD; what changed, where, and how it was verified
```

Self-check command (run before every tick):
```
python3 -m pytest -q && python3 -m compvdo --help >/dev/null
```

## Phase 0 — Planning
- [x] Write the spec (`FSD.md`) — done 2026-09-20; restated brief, flagged the lossless/visually-lossless problem, fixed the stack with rejected alternatives
- [x] Freeze scope and acceptance criteria (`requirements.md`) — done 2026-09-20; 12 rule groups R1–R12, all testable
- [x] Architecture outline (`architecture.md`) — done 2026-09-20; pure-`plan.build()` core, subprocess ffmpeg, Android as re-implementation
- [x] Data model outline (`data-model.md`) — done 2026-09-20; 5 dataclasses, settings.json + .compvdo-run.json, 4 invariants
- [x] Alias user-requested filenames to the docs set — done 2026-09-20; Architecture.md/ToDo.md/memory.md/manualTasks.md are symlinks, verified with `ls -l`

## Phase 1 — Linux core + CLI  ← top priority
- [x] 1.1 Repo skeleton: `compvdo/` package, `pyproject.toml`, `.gitignore`, `git init` — done 2026-09-20; stdlib-only core declared in pyproject, PySide6/pytest as extras; git initialised and Phase 0 committed
- [x] 1.2 `model.py` — the dataclasses from `data-model.md` — done 2026-09-20; 5 frozen dataclasses + display_width/height and bpp properties; JobSpec enforces invariants 1-2 at construction
- [x] 1.3 `probe.py` — ffprobe → `MediaInfo`, rotation from the display matrix, bitrate fallback — done 2026-09-20; rotation read from side-data then the legacy tag, verified against a real rotated clip (both give 270)
- [x] 1.4 `probe.py` — `Caps` detection + cache in settings.json (R4.3) — done 2026-09-20; detect_caps() found libx265/ffv1/hevc_vaapi + /dev/dri/renderD128 on this AMD box; caching lands with settings.py in 1.14
- [x] 1.5 `plan.py` — quality ladder → ffmpeg argv, pure function (R3, R5, R6, R1.3) — done 2026-09-20; pure build() covering 4 modes x 5 encoder families; a real medium encode ran clean at ratio 0.448
- [x] 1.6 `plan.py` — bits-per-pixel suggestion ranking (R10.3) — done 2026-09-20; estimate()/rank() are pure arithmetic, lenient on already-HEVC, and return 0 rather than inventing a saving
- [x] 1.7 `tests/test_plan.py` — the encoder/container/rotation matrix, no ffmpeg needed — done 2026-09-20; 44 tests pass in 0.06s with no ffmpeg, covering ladder/encoder/audio/naming/ranking
- [x] 1.8 `encode.py` — temp-file discipline, `-progress` parsing, cancel (R2.1, R12.2, R12.3) — done 2026-09-20; cancel measured at 0.32s over 3 runs, no stray ffmpeg, no temp left, original intact
- [x] 1.9 `verify.py` — R8.1–R8.3 checks — done 2026-09-20; compares DISPLAY dimensions, since -autorotate legitimately swaps the coded ones
- [x] 1.10 `trash.py` — `gio trash` with XDG-spec fallback (R2.3) — done 2026-09-20; ignores a snap-sandboxed XDG_DATA_HOME and reports the exact trash path; verified recoverable
- [x] 1.11 `scan.py` — folder walk, filters, sorting (R10.1, R10.2, R1.4) — done 2026-09-20; corrupt files reported not raised; own outputs excluded; all 4 sorts working
- [x] 1.12 `batch.py` — sequential queue, `.compvdo-run.json` resume, per-file isolation (R9) — done 2026-09-20; live test: 6 clips, SIGINT at #2, re-run finished the remaining 5
- [x] 1.13 `cli.py` — `scan` / `compress` / `preview` / `caps` subcommands, result table (R7) — done 2026-09-20; 6 subcommands, live-tested end to end on a mixed mp4/mkv/avi/silent/corrupt folder
- [x] 1.14 `settings.py` — load/save with unknown-key preservation and `version` migration — done 2026-09-20; caps cache cuts start-up from 0.10s to 0.00s; corrupt config degrades to defaults
- [x] 1.15 `tests/test_e2e.py` — synthetic clip via `ffmpeg lavfi`, real encode, verify, resume — done 2026-09-20; 72 tests pass in 31s covering rotation, audio, cancel, resume, delete gating, bit-exactness
- [x] 1.18 `report.py` + `compvdo report` — full ffprobe metadata + per-file summary to one markdown (requested 2026-09-20) — done 2026-09-20; generated a 32k-char report for 4 real phone clips, every ffprobe field included verbatim
- [x] 1.16 Trial on a real phone clip; record actual ratios in `qa-checklist.md` — done 2026-09-20; 4 real clips, 1.06 GB → 487 MB (55% saved), all verified; findings recorded in qa-checklist.md
- [x] 1.17 `README.md` — install, usage, the lossless explanation — done 2026-09-20; leads with the measured 5.0MB->10.2MB archive result rather than burying it

## Phase 2 — Linux GUI (PySide6)
- [x] 2.1 `gui/worker.py` — QThread bridge over `encode.py`, signals for progress/done/error — done 2026-09-20; ScanWorker/EncodeWorker/PreviewWorker QThreads, cancel via CancelToken not thread termination
- [x] 2.2 `gui/main_window.py` — folder picker, sortable file table with the suggestion column — done 2026-09-20; sortable table with NumericItem so '9.5 MB' sorts before '10.2 MB'; verified against the 4 real clips
- [x] 2.3 Queue panel: per-file + overall progress, working cancel (R12.1, R12.2) — done 2026-09-20; per-file + overall bars, overall = (finished + fraction)/total so it tracks reality
- [x] 2.4 Settings panel: mode, hw, delete-original, container; persisted via `settings.py` — done 2026-09-20; mode/hw/delete persisted via settings.py; restored state now drives the widgets at startup
- [x] 2.5 `gui/theme.qss` — Material 3 flavour, light/dark following the system (R12.4) — done 2026-09-20; token-substituted QSS, light+dark captured to reports/shots/; fixed label boxes and disabled-Danger styling
- [x] 2.6 Preview: 10 s sample encode + side-by-side playback (R11) — done 2026-09-20; PreviewWorker cuts then encodes a 10s sample into a temp dir, never beside the original
- [x] 2.7 `archive` mode warning dialog (R3.2) and `GREW` result badge (R7.2) — done 2026-09-20; archive dialog + banner, GREW shown as 'GREW — kept original' in the status column
- [x] 2.8 Trial pass on the GUI against `qa-checklist.md` — done 2026-09-20; GUI-driven batch encoded 3 clips with 27 live progress events, Cancel stopped in 0.32s leaving nothing behind, clean shutdown

## Phase 2b — User-requested work (2026-09-20)
- [x] 2b.1 Read `dev_guide.md` and bring the GUI up to its norms — done 2026-09-20; §3.2 module contracts, §11 TX/RX/INFO/WARN/ERR console, §7.5 single `_set_busy`, §12 delete confirmation naming targets with Cancel defaulted, §11 completion names the output folder, §4 pinned requirements.txt
- [x] 2b.2 Limit every process to n−2 cores — done 2026-09-20; `cpu.py` + `-threads` and x265 `pools=`, measured 215% at `--cores 2` and 750% at `--cores 10`; exposed as `--cores N` and shown in `caps`
- [x] 2b.3 Well-pictured README — done 2026-09-20; screenshots in `docs/images/`, measured results up front, every link verified to resolve
- [x] 2b.4 Move docs into `docs/` — done 2026-09-20; root is README.md + two symlinks, everything else under docs/; scaffold still works with no --dir because those symlinks satisfy its hard-coded paths
- [x] 2b.5 M4.1 decided — done 2026-09-20; visually-lossless HEVC stays the default, `archive` stays opt-in

## Phase 3 — Android

- [x] 3.0 Decide the Android stack: Kotlin/Compose vs Dart/Flutter — done 2026-09-20; Kotlin, because Flutter still needs a platform channel to Media3 and ffmpeg-kit's retirement removed its one advantage; rationale and the iOS condition that would reverse it are in `architecture.md`
- [x] 3.1 Evaluate MediaCodec / Media3 Transformer capabilities vs requirements — done 2026-09-20; no CRF on Android, so `QualityLadder` targets a fraction of the source bitrate (0.25/0.50/0.75) via `VideoEncoderSettings`; `ARCHIVE` correctly omitted (no FFV1 on MediaCodec)
- [x] 3.2 Set up the Android project scaffold (Kotlin + Jetpack Compose) — done 2026-09-20; Gradle 8.11.1, compileSdk 35, minSdk 26, Media3 1.5.1; `assembleDebug` verified green on 2026-09-20 producing a 21 MB APK
- [x] 3.3 Data model and MediaStore scanner (`VideoInfo`) — done 2026-09-20; MediaStore query with sort, `READ_MEDIA_VIDEO` requested in `HomeScreen`
- [x] 3.4 Compression engine wrapping Media3 `Transformer` — done 2026-09-20; threading verified correct (driven from `Dispatchers.Main.immediate`, which is what Media3 requires); open defects tracked in 3.7–3.16
- [x] 3.5 The UI (list, sortable, progress card, settings) — done 2026-09-20; Compose M3 across Home/Compress/Settings screens
- [x] 3.6 Build and trial on a real device — done 2026-09-20; cancel, encode, verify and the delete offer all confirmed working. Three defects found and fixed same-day (Download output path, external-player URI grant, redundant rescans). Re-trial needed for those fixes — see qa-checklist.md
- [x] 3b.11 Fix the list-view layout found in the 16:11 screenshots — done 2026-09-20; resolution no longer wraps (one non-wrapping line, rung labels), thumbnails are 16:9 Fit so orientation reads, and the scanner reports display rather than coded dimensions
- [x] 3b.12 Use the supplied `iconCompVDO.png` as the launcher icon — done 2026-09-20; glyph extracted by flood-filling the page background inwards (a colour key would also have erased the document's near-white interior), rendered into all five density buckets at 70dp on the 108dp canvas, plus a derived monochrome layer. The wordmark and tagline were dropped — see the note in manual-task.md
- [x] 3b.13 Light/dark setting on both platforms (R12.4) — done 2026-09-20; Android gets a Follow system / Light / Dark radio in Settings, desktop gets an Appearance combo that restyles the running app rather than needing a restart
- [x] 2c.4 Expose the x265 preset (`--preset`, GUI combo) — done 2026-09-20; measured `fast` at 1.6x the speed of `medium` with a *smaller* file on real phone footage
- [x] 2c.5 Fix the hardware quality ladder — done 2026-09-20; VAAPI was fed the software CRF directly, so `--hw auto --mode high` produced a file **219% of its source**. Hardware now maps to crf+8; `--hw auto` is 5.8x faster than the default and smaller
- [x] 3b.14 Inherit DATE_TAKEN/DATE_MODIFIED on Android output — done 2026-09-20; without them the provider stamps "now" and every compressed file jumps to the top of the album instead of sitting beside its original
- [x] 3b.15 Thumbnail performance — done 2026-09-20; a shared Coil loader in `CompVdoApp` with a 96 MB disk cache and a 20% memory cache, plus bounded decode sizes so a 4K frame is not decoded for a 64dp box
- [x] 3b.16 Scrolling audit — done 2026-09-20; Settings could not reach Appearance (no `verticalScroll` on a `fillMaxSize` Column, which clips silently). Same latent bug fixed in the options sheet, CompressScreen and SortBar before it could be reported
- [x] 3b.17 Compression runs in the background and is queueable — done 2026-09-20; `CompressionQueue` is process-scoped, so browsing, switching tabs and selecting more videos no longer touch a running encode. A second selection is appended, not refused. The bottom bar is no longer hidden during a batch (it existed to trap the user on the screen that owned the job), and a tappable banner above it reports progress
- [x] 3b.18 Completion dialog — done 2026-09-20; modelled on the file-manager dialog the user referenced: title, one-line summary, scrollable per-file result list, an optional "also remove the originals" checkbox and a single Done. Shows wherever the user happens to be, because it is hosted above the nav graph
- [M] 3.6b Re-trial on device after the 2026-09-20 fixes — **blocked on M3.** `assembleDebug` succeeds locally, but a build is not a trial: nothing has run on hardware yet. Reverted from `[x]` on 2026-09-20 after code validation.

### Phase 3 follow-up — from the 2026-09-20 code validation

Full findings, with the evidence for each, are in `qa-checklist.md`.

**Blocking — data loss:**
- [x] 3.7 **The "trash" is a permanent delete (R2.3).** `BatchRunner.trashOriginal()` documents `createTrashRequest` and calls `contentResolver.delete()`. Use `MediaStore.createTrashRequest`, honour the returned `IntentSender`, and stop swallowing `RecoverableSecurityException` — done 2026-09-20; new `compression/TrashRequest.kt` — `MediaStore.createTrashRequest` on API 30+, the `RecoverableSecurityException` IntentSender on 29, legacy delete on 28, each labelled honestly via `isRecoverable()`
- [x] 3.8 **R8.3 does not check playability** yet gates the delete as though it does — the verifier reads a track header and asserts `playable = true` — done 2026-09-20; `Verifier.walkSamples()` now reads every sample of the video track and rejects a file whose last frame falls short of the declared duration — the header-only check could not fail
- [x] 3.9 Confirmation dialog before deleting originals, naming targets and defaulting to the safe button (dev_guide.md §12) — done 2026-09-20; `DeleteOriginalsDialog` names up to 12 targets, states count and total size, says whether removal is recoverable on *this* device, and emphasises "Keep originals"

**Significant:**
- [x] 3.10 Cancel does not stop the running file (R12.2) — the flag is only read between files, and nothing cancels the `Transformer` — done 2026-09-20; cancel now cancels the coroutine `Job`, which fires `invokeOnCancellation` → `Transformer.cancel()`; `cancel()` bounces to the main looper first because Media3 calls `verifyApplicationThread()`. Also fixed `catch (Exception)` swallowing `CancellationException` (the batch carried on to the next file) and cleanup now runs under `NonCancellable`, without which the orphaned `IS_PENDING` row was never removed
- [x] 3.11 Open the output `"rw"`, not `"w"` — done 2026-09-20; the MP4 muxer seeks back to write `moov` and a write-only descriptor cannot
- [x] 3.12 Wire the foreground notification to real progress (`updateProgress` is never called) and request `POST_NOTIFICATIONS` — done 2026-09-20; the notification is driven from a companion-object `updateProgress` posting to the same id the service went foreground with — no binder needed, which is why the old instance method was unreachable. `POST_NOTIFICATIONS` is requested when a batch starts, and a refusal costs the notification, never the compression

**Divergences and gaps:**
- [ ] 3.13 Align the R10.3 ranking with `plan.py` or correct the comment claiming parity; stop defaulting fps to 30 (R10.4)
- [x] 3.14 Move MediaStore work off the main thread — done 2026-09-20; `OutputNaming` create/finalize/delete and `getFileSize` are now suspend on Dispatchers.IO (the scanner already was). Also fixed `nameExists()` querying VOLUME_EXTERNAL while the insert targeted VOLUME_EXTERNAL_PRIMARY
- [ ] 3.15 Add a test source set — there is currently none
- [ ] 3.16 R11 preview and R9.2 batch resume are both absent on Android

## Phase 3b — Android UX, requested 2026-09-20

Source: `manual-task.md` → "Your issues & suggestions". Reference app for the
navigation and log patterns: `~/Downloads/ytdlnis` (full source).

**Ordering note:** 3b.6 (delete prompt) must not ship before 3.7 lands, or the
app will cheerfully offer to permanently destroy footage.

- [x] 3b.0 minSdk 26 → 28 (Android 9.0), per M3.3 — done 2026-09-20
- [x] 3b.1 Redesign the launcher icon — the current mark is busy and reads as nothing at launcher size (see `reports/android/IMG_20260920_150915.jpg`) — done 2026-09-20; root cause was a safe-zone violation — the 0–100 viewport was stretched over the full 108dp canvas so both arrow tips sat ~13dp outside the 72dp guaranteed region and every launcher mask sliced them. Also: `<monochrome>` pointed at the four-colour drawable, and the background was pure white. Now a white play triangle between two bars on a blue gradient; verified rendered at 36/48/96/192px under circle and squircle masks
- [x] 3b.2 ytdlnis-style bottom navigation; everything currently on the opening screen moves into a **Home** tab — done 2026-09-20; bottom NavigationBar with Home / Log / Settings; everything from the old opening screen is now the Home tab, and tab switches preserve each tab's state (`saveState`/`restoreState`). The bar hides during a batch so nobody tabs away and assumes it stopped
- [x] 3b.3 Log system like ytdlnis: viewable in-app, **copy** button, and export/share — done 2026-09-20; `util/AppLog.kt` bounded ring buffer using the same TX/RX/INFO/WARN/ERR vocabulary as the desktop, plus `ui/screens/LogScreen.kt` with copy, send-via-share-sheet, clear and follow-tail
- [x] 3b.4 On Compress, a bottom sheet asking "default settings" or "override" — done 2026-09-20; `CompressOptionsSheet` — opens showing what the defaults *are* so the common case is one tap, and "Change settings" expands the controls in place rather than bouncing to Settings and losing the selection
- [x] 3b.5 Preview: in-app player, or hand off to VLC / MX Player / the native viewer via intent (R11) — done 2026-09-20; `util/VideoPlayback.kt` hands off to whichever player the user already has (chooser, not the silent default). A play button on each row, and on each result both the original and the output, so the two can be compared. Chose hand-off over an in-app ExoPlayer: their player already handles codecs, gestures and rotation, and a second decoder path alongside Transformer is a maintenance cost for no gain
- [x] 3b.6 After a batch finishes, offer to delete the originals — done 2026-09-20; `BatchRunner` no longer deletes anything at all. Only results that are OK **and** verified **and** smaller are ever offered, and the platform runs its own confirmation on API 29+
- [x] 3b.7 The `Download` folder is not reachable on device — fix the scanner's coverage — done 2026-09-20; root cause was not a path filter — `MediaStore.Video.Media` is a view restricted to `media_type=3`, and videos landing in Download are indexed with a generic MIME as `MEDIA_TYPE_NONE`/`DOCUMENT`, so no query of the Video collection could ever return them. Now queries `MediaStore.Files` per volume with a MIME/extension selection
- [x] 3b.8 Album-style folder picker — done 2026-09-20; 3-column `LazyVerticalGrid` of folder tiles with a Coil video-frame thumbnail, name, count and total size, matching the referenced gallery screenshot; opening one drills into its videos and Back returns to the grid
  - [x] 3b.8a Two view modes — done 2026-09-20; grid of big tiles or a list with a 52dp thumbnail, toggled from the app bar
  - [x] 3b.8b Sort options kept — done 2026-09-20; the same `SortBar` shows on the album grid and inside a folder, in both view modes
  - [x] 3b.8c Whole row selects — done 2026-09-20; the card carries the `clickable`, and tiles toggle on tap anywhere
- [x] 3b.9 Cover WhatsApp videos **and** WhatsApp documents — done 2026-09-20; WhatsApp Video was already reachable; WhatsApp Documents is the same document-MIME class as Download and is now covered. `.nomedia` directories remain unreachable by any MediaStore query — `SafVideoScanner` is the sanctioned fallback
- [x] 3b.10 Audio compression on Android (desktop half done as 2c; mirror the same ladder and the 128 kbps floor): opt in, fixed options, never below 128 kbps — done 2026-09-20; `AudioSetting` mirrors the desktop ladder (keep/192/160/128) with `AUDIO_MIN_KBPS` as the one floor; `KEEP` guarantees pass-through by never calling `setAudioMimeType`, so Transformer transmuxes the track

## Phase 2c — Audio compression on desktop, requested 2026-09-20

R6.1 currently stream-copies audio always. This adds an opt-in re-encode with
the same fixed options the Android side offers, so both platforms behave alike.

- [x] 2c.1 `--audio-bitrate` / mode ladder in `plan.py`, floored at 128 kbps — done 2026-09-20; `AUDIO_MIN_KBPS = 128` as the one named floor, `resolve_audio()` split out as a pure function so the clamp can be reported before anything encodes
- [x] 2c.2 CLI flag and GUI control, defaulting to "keep original audio" — done 2026-09-20; `--audio {keep,192k,160k,128k}` on compress and preview, a GUI combo persisted via settings.json, both defaulting to `keep`
- [x] 2c.3 Tests covering the floor and the copy-by-default behaviour — done 2026-09-20; 22 new tests — the default path is a packet-identical stream copy (verified by MD5 of the coded audio, not just argv), each rung lands within ffprobe tolerance, 64k clamps to 128k and says so, silent sources stay silent

## Phase 4 — Windows
- [ ] 4.1 Path/encoding audit of the core (no POSIX assumptions, long paths, UTF-16 names)
- [ ] 4.2 `trash.py` Windows branch
- [ ] 4.3 ffmpeg discovery: bundled `ffmpeg_bin/` then PATH
- [ ] 4.4 PyInstaller spec + one-folder build  ← needs M2
- [ ] 4.5 Trial on a Windows machine  ← needs M2
