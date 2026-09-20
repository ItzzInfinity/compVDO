# Roadmap & Progress Tracker  (aliased as `ToDo.md`)

Source of truth for task status. **Update after every completed task** — this
file, not the code, answers "what is done?".

Legend: `[ ]` pending · `[~]` in progress · `[x]` done · `[M]` waiting on a
manual step (see `../manual-task.md`)

Tick format — date and a one-line summary, every time:

```
- [x] Task name — done YYYY-MM-DD; what changed, where, and how it was verified
```

Self-check command (run before every tick):
```
python3 -m pytest -q && python3 -m compvdo --help >/dev/null
```

## Phase 0 — Planning
- [x] Write the spec (`../FSD.md`) — done 2026-09-20; restated brief, flagged the lossless/visually-lossless problem, fixed the stack with rejected alternatives
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
- [ ] 1.8 `encode.py` — temp-file discipline, `-progress` parsing, cancel (R2.1, R12.2, R12.3)
- [ ] 1.9 `verify.py` — R8.1–R8.3 checks
- [ ] 1.10 `trash.py` — `gio trash` with XDG-spec fallback (R2.3)
- [ ] 1.11 `scan.py` — folder walk, filters, sorting (R10.1, R10.2, R1.4)
- [ ] 1.12 `batch.py` — sequential queue, `.compvdo-run.json` resume, per-file isolation (R9)
- [ ] 1.13 `cli.py` — `scan` / `compress` / `preview` / `caps` subcommands, result table (R7)
- [ ] 1.14 `settings.py` — load/save with unknown-key preservation and `version` migration
- [ ] 1.15 `tests/test_e2e.py` — synthetic clip via `ffmpeg lavfi`, real encode, verify, resume
- [ ] 1.16 Trial on a real phone clip; record actual ratios in `qa-checklist.md`  ← needs M1
- [ ] 1.17 `README.md` — install, usage, the lossless explanation

## Phase 2 — Linux GUI (PySide6)
- [ ] 2.1 `gui/worker.py` — QThread bridge over `encode.py`, signals for progress/done/error
- [ ] 2.2 `gui/main_window.py` — folder picker, sortable file table with the suggestion column
- [ ] 2.3 Queue panel: per-file + overall progress, working cancel (R12.1, R12.2)
- [ ] 2.4 Settings panel: mode, hw, delete-original, container; persisted via `settings.py`
- [ ] 2.5 `gui/theme.qss` — Material 3 flavour, light/dark following the system (R12.4)
- [ ] 2.6 Preview: 10 s sample encode + side-by-side playback (R11)
- [ ] 2.7 `archive` mode warning dialog (R3.2) and `GREW` result badge (R7.2)
- [ ] 2.8 Trial pass on the GUI against `qa-checklist.md`

## Phase 3 — Windows
- [ ] 3.1 Path/encoding audit of the core (no POSIX assumptions, long paths, UTF-16 names)
- [ ] 3.2 `trash.py` Windows branch
- [ ] 3.3 ffmpeg discovery: bundled `ffmpeg_bin/` then PATH
- [ ] 3.4 PyInstaller spec + one-folder build  ← needs M2
- [ ] 3.5 Trial on a Windows machine  ← needs M2

## Phase 4 — Android
- [ ] 4.1 Confirm Media3 `Transformer` HEVC + CRF-equivalent story; write the findings into `architecture.md`
- [ ] 4.2 Compose M3 skeleton: permissions, MediaStore video query, sortable list
- [ ] 4.3 Transformer job runner with progress + cancel, foreground service
- [ ] 4.4 Output naming (R1) via MediaStore, trash-based delete (R2.3)
- [ ] 4.5 Batch queue + suggestion ranking, sharing the R10.3 arithmetic
- [ ] 4.6 Build and trial on a device  ← needs M3
