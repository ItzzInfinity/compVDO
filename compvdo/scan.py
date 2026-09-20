"""Walk a folder, probe what is there, rank it (R10)."""

from __future__ import annotations

from collections.abc import Callable, Iterable
from pathlib import Path

from .model import INPUT_EXTS, Caps, MediaInfo, ScanEntry
from .plan import is_compressed_output, rank
from .probe import ProbeError, info as probe_info

SORTS = ("savings", "size", "date", "name")


def find_videos(root: Path, *, recursive: bool = True,
                include_compressed: bool = False) -> list[Path]:
    """Every candidate file under `root`, in a stable order."""
    root = Path(root)
    if root.is_file():
        return [root] if _is_candidate(root, include_compressed) else []
    it: Iterable[Path] = root.rglob("*") if recursive else root.glob("*")
    return sorted(p for p in it if p.is_file() and _is_candidate(p, include_compressed))


def _is_candidate(p: Path, include_compressed: bool) -> bool:
    if p.suffix.lower().lstrip(".") not in INPUT_EXTS:
        return False
    if p.name.startswith(".compvdo-tmp-"):
        return False
    return include_compressed or not is_compressed_output(p)    # R1.4


def scan(root: Path, caps: Caps, *, mode: str = "medium", recursive: bool = True,
         include_compressed: bool = False,
         on_file: Callable[[int, int, Path], None] | None = None,
         should_continue: Callable[[], bool] | None = None,
         ) -> tuple[list[ScanEntry], list[tuple[Path, str]]]:
    """Probe every candidate. Returns (ranked entries, skipped with reasons).

    Unreadable files are reported, never raised: one corrupt clip in a folder
    of 500 must not stop the scan.
    """
    paths = find_videos(root, recursive=recursive, include_compressed=include_compressed)
    infos: list[MediaInfo] = []
    skipped: list[tuple[Path, str]] = []
    for n, p in enumerate(paths, 1):
        if should_continue is not None and not should_continue():
            break                       # the caller moved on; stop probing
        if on_file:
            on_file(n, len(paths), p)
        try:
            infos.append(probe_info(p, caps))
        except (ProbeError, OSError) as e:
            skipped.append((p, str(e)))
    return rank(infos, mode), skipped


def sort_entries(entries: list[ScanEntry], by: str = "savings",
                 reverse: bool = False) -> list[ScanEntry]:
    """R10.2. 'savings' is the suggestion rank; the others are plain fields."""
    if by not in SORTS:
        raise ValueError(f"unknown sort {by!r}; expected one of {SORTS}")
    keys = {
        "savings": lambda e: e.rank,
        "size": lambda e: -e.info.size,
        "date": lambda e: -e.info.mtime,
        "name": lambda e: e.info.path.name.lower(),
    }
    return sorted(entries, key=keys[by], reverse=reverse)
