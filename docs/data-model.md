# Data model

No database. Three dataclass families in memory, two JSON files on disk.

## In memory (`compvdo/model.py`)

```python
@dataclass(frozen=True)
class MediaInfo:
    path: Path
    size: int              # bytes
    duration: float        # seconds, from format.duration
    width: int; height: int          # coded, before rotation
    rotation: int          # 0/90/180/270, from display matrix
    fps: float
    vcodec: str            # 'h264', 'hevc', ...
    acodec: str | None
    vbitrate: int          # bits/s; falls back to size*8/duration
    mtime: float
    container: str         # source extension, lowercased, no dot

@dataclass(frozen=True)
class Caps:                # probed once, cached
    ffmpeg: Path; ffprobe: Path; ffmpeg_version: str
    encoders: frozenset[str]     # 'libx265', 'hevc_vaapi', 'ffv1', ...
    vaapi_device: Path | None
    probed_at: float

@dataclass(frozen=True)
class JobSpec:
    src: MediaInfo
    mode: str              # low|medium|high|archive
    crf: int | None        # overrides the ladder (R3.4)
    hw: str                # 'off' | 'auto'
    container: str | None  # override (R1.3)
    delete_original: bool
    dst: Path              # resolved via R1.1/R1.2

@dataclass
class JobResult:
    spec: JobSpec
    status: str            # ok | grew | failed | cancelled | skipped
    dst_size: int | None
    seconds: float
    verified: bool
    deleted: bool
    message: str           # error tail or note
    @property
    def ratio(self) -> float | None      # dst_size / src.size

@dataclass(frozen=True)
class ScanEntry:
    info: MediaInfo
    bpp: float             # vbitrate / (w*h*fps)   -- R10.3
    est_saving: int        # bytes, estimate only   -- R10.4
    rank: int              # 0 = compress this first
```

**Why `MediaInfo` is frozen and separate from `JobSpec`:** probing is expensive
and the same file may be planned several times (preview, then real run, then a
different mode). Probe once, plan many.

**Why `rank` is stored, not computed on read:** sorting by rank in the GUI must
be stable while the list is being re-sorted by other columns.

## On disk

### `~/.config/compvdo/settings.json`  (Windows: `%APPDATA%\compvdo\`)
```json
{
  "version": 1,
  "defaults": { "mode": "medium", "hw": "off", "delete_original": false,
                "jobs": 1, "container": null },
  "ui": { "theme": "system", "last_folder": "/home/u/Videos", "sort": "savings" },
  "caps": { "ffmpeg_version": "4.4.2", "encoders": ["libx265", "..."],
            "vaapi_device": "/dev/dri/renderD128", "probed_at": 1758300000.0 }
}
```
- `caps` is a **cache**, re-probed when `ffmpeg_version` changes or after 30
  days. A stale cache must never cause a failure — any planning error with a
  cached capability triggers a re-probe and one retry.
- Unknown keys are preserved on write, so a newer version's settings survive a
  downgrade. `version` gates migrations.

### `<batch root>/.compvdo-run.json`  (R9.2, resumable batch)
```json
{ "started": 1758300000.0, "mode": "medium",
  "done":   { "/abs/path/a.mp4": {"status":"ok","dst_size":41234567} },
  "failed": { "/abs/path/b.mov": "codec not supported by container" } }
```
- Keyed by absolute source path. Re-running a batch skips every key in `done`.
- Deleted automatically when the batch completes with nothing left pending.
- Lives beside the media, not in config, so moving/copying the folder carries
  its own progress — and so two different folders never collide.

## Invariants
1. `JobSpec.dst` is always in `src.path.parent` (R1.1) and never equals
   `src.path`.
2. The temp file is always in the same directory as `dst`, so `os.replace` is
   atomic (never crosses a filesystem boundary).
3. `JobResult.deleted` is true only if `verified` is true (R8.4).
4. `ScanEntry.est_saving` is never presented without the word "estimated".
