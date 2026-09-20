# Architecture

## Shape

```
                    ┌────────────────────────────┐
  CLI (argparse) ──▶│                            │
                    │        compvdo.core        │──▶ ffmpeg / ffprobe
  GUI (PySide6)  ──▶│  scan · plan · run · verify│    (subprocess)
                    └────────────────────────────┘
                              ▲
                              │ same rules, different code
                    ┌─────────┴──────────┐
                    │  Android (Kotlin)  │──▶ Media3 Transformer
                    │  Compose + M3      │    (MediaCodec, hardware)
                    └────────────────────┘
```

The desktop core is a **library with no UI imports**. The CLI and the GUI are
both thin callers. Anything a GUI needs (progress, cancel, logs) is exposed as
callbacks, never as prints.

## Module layout (desktop)

```
compvdo/
  __init__.py
  probe.py      # ffprobe wrapper -> MediaInfo; encoder capability detection
  model.py      # dataclasses: MediaInfo, JobSpec, JobResult, ScanEntry, Caps
  plan.py       # quality ladder -> ffmpeg argv; suggestion ranking (R10.3)
  encode.py     # run ffmpeg, parse -progress, cancel, temp-file discipline
  verify.py     # R8 checks
  scan.py       # walk a folder -> [ScanEntry], sorting
  batch.py      # queue, resume state, per-file isolation
  trash.py      # platform-correct delete (R2.3)
  settings.py   # load/save user defaults + capability cache
  report.py     # full-metadata markdown reports, incl. before/after
  cli.py        # argparse front-end
  gui/
    app.py      # QApplication bootstrap, theme
    main_window.py
    worker.py   # QThread bridge over encode.py
    theme.qss   # Material-flavoured stylesheet
tests/
  test_plan.py test_verify.py test_scan.py  # unit, no ffmpeg
  test_e2e.py                               # generates a synthetic clip, real ffmpeg
ffmpeg_bin/     # Windows only: bundled ffmpeg.exe (gitignored)
```

## The flow, end to end

1. **Discover** — `scan.walk(root)` filters by extension (R1.4 excludes
   `_compressed`), `probe.info()` each file. ffprobe is ~20 ms/file, so a 500
   file folder scans in well under a second; still done on a worker thread in
   the GUI.
2. **Rank** — `plan.suggest()` computes bits-per-pixel and estimated savings
   (R10.3). Pure arithmetic, unit-testable, no ffmpeg.
3. **Plan** — `plan.build(job)` turns `(MediaInfo, mode, hw, container)` into a
   concrete ffmpeg argv list. **This function is the heart of the project and
   has no side effects** — it is a pure `inputs -> list[str]`, which is why the
   whole encoder matrix is testable without encoding anything.
4. **Encode** — `encode.run(argv, on_progress, cancel_token)`:
   - writes to `dir/.compvdo-tmp-<pid><ext>` (R2.1)
   - passes `-progress pipe:1 -nostats`, reads `out_time_us=` lines, divides by
     the probed duration → real percent (R12.3)
   - `cancel_token` set → `terminate()`, 1 s grace, `kill()`, unlink temp
   - exit 0 → `os.replace()` temp into final name (atomic, same filesystem)
5. **Verify** — `verify.check(src, dst)` runs R8.1–R8.3.
6. **Settle** — report (R7); if `delete_original` **and** verify passed →
   `trash.send(src)`.

## Key decisions and the rejected alternative

**Subprocess ffmpeg, not bindings.** Distro ffmpeg is what users already have,
is GPL-licensed as an external program (no linking question), and upgrades
independently of us. PyAV pins us to an ABI and breaks on every distro bump.

**Pure `plan.build()`.** The alternative — building the command string inside
the encode loop — is how every ffmpeg wrapper becomes untestable. Keeping it
pure means the encoder/container/rotation/hw matrix is covered by fast unit
tests and only a couple of real encodes are needed end to end.

**Sequential batch by default.** x265 already uses every core; running 4 jobs at
once makes each 4× slower and the total no faster, while quadrupling peak disk
use. `--jobs` exists for the hardware-encode case, where the fixed-function
block is the bottleneck and the CPU is idle.

**Software x265 as the default, hardware opt-in.** VAAPI/NVENC at matched
quality produce noticeably larger files. The product's promise is "smaller with
no visible loss", so the default must optimise for that; speed is the flag.

**Progress from `-progress`, not from parsing stderr.** stderr's `frame= … time=`
line is a carriage-return-overwritten human display that changes between ffmpeg
versions; `-progress` is a stable key=value stream designed for this.

**Android is a re-implementation.** See the FSD stack table: ffmpeg-kit is
retired. Media3 `Transformer` gives hardware HEVC with no native packaging, no
GPL, and no 200 MB APK — at the cost of no FFV1, so **`archive` mode does not
exist on Android** and the UI must not offer it.

## Why Android is Kotlin, not Flutter (decided 2026-09-20)

The question asked was whether a Dart/Flutter stack would be better for
cross-OS builds. It would not, for this application, for one reason:

**The UI is not the hard part. The encoder is, and Flutter does not help with
the encoder.**

On Android the encode must go through Media3 `Transformer` (an AndroidX
library, Kotlin/Java-first) or a bundled ffmpeg. From Flutter, both need a
platform channel — so the Kotlin gets written anyway, plus Dart, plus channel
glue, plus marshalling progress and cancel across an `EventChannel`. Flutter
adds a layer without removing the difficulty.

The ffmpeg escape hatch that made Flutter attractive for video work is gone:
`ffmpeg_kit_flutter` was the standard answer, and ffmpeg-kit was retired in
January 2025 with its prebuilt binaries withdrawn. Community forks are
unmaintained, and Play's 16 KB page-size requirement for apps targeting
Android 15+ breaks stale prebuilt `.so` bundles. *(Re-verify before relying on
this; it is the kind of fact that moves.)*

| Concern | Kotlin + Compose | Flutter |
|---|---|---|
| Media3 `Transformer` | direct API call | platform channel, written in Kotlin |
| MediaStore, scoped storage, trash intent | direct | a channel each |
| Foreground service for long encodes | native | plugin or own channel; keeping an isolate alive while backgrounded is awkward |
| Progress + cancel | Kotlin `Flow` | `Flow` → `EventChannel` → Dart `Stream` |
| Desktop | not needed — PySide6 is done and trialled | would mean rewriting a working GUI |
| iOS | a second native app | **one codebase — Flutter's real win** |

**What would flip this decision: iOS.** If an iPhone version is wanted, Flutter
avoids a whole second native application rather than just a UI layer, and the
calculation changes. It is not currently on the roadmap.

**Rejected middle path: Kotlin Multiplatform + Compose Multiplatform.** It
shares the *logic* — the quality ladder, the bits-per-pixel ranking, the
verification rules — across Android and desktop while keeping direct native
Media3 access. That is a better code-sharing story than Flutter for this app.
It is rejected for now only because it means reimplementing the Python core in
Kotlin, and that core is stdlib-only, tested and working. Revisit if a second
native platform ever needs the same logic.

**Consequence, unchanged:** Android implements the rules in
`requirements.md`, not this code. That document exists so two implementations
can be checked against one spec.

## Platform notes

| | ffmpeg source | GUI | Delete |
|---|---|---|---|
| Linux | system package; hard requirement, checked at start-up with an actionable message | PySide6 | `gio trash`, fallback `~/.local/share/Trash` spec |
| Windows | bundled `ffmpeg.exe` beside the exe, found via `sys._MEIPASS` | PySide6, same code | `SHFileOperation` via `send2trash` if present, else confirm-and-unlink |
| Android | none (Media3) | Compose M3 | `MediaStore.createTrashRequest` |

## Temp files belong to a process

`.compvdo-tmp-<pid><ext>` encodes the owning process id, and
`cleanup_stale_temps()` only removes a temp whose process is gone. This is not
tidiness — deleting indiscriminately destroys a running encode. ffmpeg holds an
open descriptor, so it keeps writing happily to the unlinked inode, reports
success, and the output simply is not there. Observed in this project when a
second instance was started against the same folder.

Note the remaining gap: two instances told to compress the *same file* will
each write their own temp and race to `os.replace()` the same destination. The
temp-file fix removes the destructive case; a folder lock would remove the race
and is not implemented.

## Error policy
- ffmpeg not found → one clear message naming the install command, exit 2.
- A file fails → recorded in the result table, batch continues (R9.3).
- Anything unexpected → the full ffmpeg stderr tail (last 20 lines) is shown.
  Never swallow an encoder error into a generic "compression failed".
