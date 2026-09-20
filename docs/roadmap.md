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
- [M] 3.6 Build and trial on a real device — **blocked on M3.** `assembleDebug` succeeds locally, but a build is not a trial: nothing has run on hardware yet. Reverted from `[x]` on 2026-09-20 after code validation.

### Phase 3 follow-up — from the 2026-09-20 code validation

Full findings, with the evidence for each, are in `qa-checklist.md`.

**Blocking — data loss:**
- [ ] 3.7 **The "trash" is a permanent delete (R2.3).** `BatchRunner.trashOriginal()` documents `createTrashRequest` and calls `contentResolver.delete()`. Use `MediaStore.createTrashRequest`, honour the returned `IntentSender`, and stop swallowing `RecoverableSecurityException`
- [ ] 3.8 **R8.3 does not check playability** yet gates the delete as though it does — the verifier reads a track header and asserts `playable = true`
- [ ] 3.9 Confirmation dialog before deleting originals, naming targets and defaulting to the safe button (dev_guide.md §12)

**Significant:**
- [ ] 3.10 Cancel does not stop the running file (R12.2) — the flag is only read between files, and nothing cancels the `Transformer`
- [ ] 3.11 Open the output `"rw"`, not `"w"` — MP4 muxing must seek back to write `moov`
- [ ] 3.12 Wire the foreground notification to real progress (`updateProgress` is never called) and request `POST_NOTIFICATIONS`

**Divergences and gaps:**
- [ ] 3.13 Align the R10.3 ranking with `plan.py` or correct the comment claiming parity; stop defaulting fps to 30 (R10.4)
- [ ] 3.14 Move MediaStore work off the main thread
- [ ] 3.15 Add a test source set — there is currently none
- [ ] 3.16 R11 preview and R9.2 batch resume are both absent on Android

## Phase 4 — Windows
- [ ] 4.1 Path/encoding audit of the core (no POSIX assumptions, long paths, UTF-16 names)
- [ ] 4.2 `trash.py` Windows branch
- [ ] 4.3 ffmpeg discovery: bundled `ffmpeg_bin/` then PATH
- [ ] 4.4 PyInstaller spec + one-folder build  ← needs M2
- [ ] 4.5 Trial on a Windows machine  ← needs M2
