"""Deleting an original, safely (R2.3).

'Delete' means 'move to the system trash' wherever a trash exists. A hard
unlink is a separate, explicit act.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import time
import urllib.parse
from pathlib import Path


class TrashError(RuntimeError):
    pass


def send(path: Path, *, purge: bool = False) -> str:
    """Trash (or with purge=True, unlink) one file. Returns what was done."""
    path = Path(path)
    if not path.exists():
        raise TrashError(f"{path} does not exist")
    if purge:
        path.unlink()
        return "purged"

    if os.name == "nt":
        return _windows_recycle(path)
    return _xdg_trash(path)


# --- Linux ---------------------------------------------------------------

def data_home() -> Path:
    """`~/.local/share`, ignoring a snap-sandboxed XDG_DATA_HOME.

    Measured on this machine: a terminal inside the VS Code snap exports
    XDG_DATA_HOME=/home/u/snap/code/263/.local/share. `gio trash` honours it,
    reports success, and the video lands in a per-snap trash directory that the
    user's file manager never shows. The file is technically recoverable and
    practically lost, which is exactly the outcome R2.3 exists to prevent.
    """
    raw = os.environ.get("XDG_DATA_HOME")
    if raw:
        candidate = Path(raw)
        parts = candidate.parts
        sandboxed = "snap" in parts and str(candidate).startswith(str(Path.home()))
        if not sandboxed:
            return candidate
    return Path.home() / ".local" / "share"


def trash_dir() -> Path:
    return data_home() / "Trash"


def _xdg_trash(path: Path) -> str:
    # gio is present on any GNOME/GTK system and gets the trash spec right,
    # including the volume-local .Trash-$uid case. Prefer it.
    # gio and trash-cli both honour XDG_DATA_HOME, so under a snap sandbox they
    # would hide the file (see data_home()). Only trust them when the
    # environment is not redirecting us somewhere invisible.
    env_is_sane = str(data_home()) == os.environ.get("XDG_DATA_HOME", str(data_home()))
    if env_is_sane:
        for tool, label in ((shutil.which("gio"), "gio"), (shutil.which("trash-put"), "trash-cli")):
            if not tool:
                continue
            argv = [tool, "trash", str(path)] if label == "gio" else [tool, str(path)]
            proc = subprocess.run(argv, capture_output=True, text=True)
            if proc.returncode == 0:
                return f"trashed to {trash_dir() / 'files'} ({label})"
    return _manual_xdg_trash(path)


def _manual_xdg_trash(path: Path) -> str:
    """The freedesktop.org Trash spec by hand, for headless systems.

    Only handles the home trash: cross-device moves are refused rather than
    silently turned into a copy-and-delete, because a half-finished copy of a
    4 GB video is a worse outcome than an honest error.
    """
    home_trash = trash_dir()
    files_dir, info_dir = home_trash / "files", home_trash / "info"

    if path.stat().st_dev != _dev_of(files_dir):
        raise TrashError(
            f"{path} is on a different filesystem from the trash; "
            "use --purge if you really want it deleted outright"
        )

    files_dir.mkdir(parents=True, exist_ok=True)
    info_dir.mkdir(parents=True, exist_ok=True)

    name, n = path.name, 1
    while (files_dir / name).exists() or (info_dir / f"{name}.trashinfo").exists():
        name = f"{path.stem}.{n}{path.suffix}"
        n += 1

    # Write the .trashinfo first: a trashed file with no info record is an
    # orphan the file manager cannot restore.
    (info_dir / f"{name}.trashinfo").write_text(
        "[Trash Info]\n"
        f"Path={urllib.parse.quote(str(path.resolve()))}\n"
        f"DeletionDate={time.strftime('%Y-%m-%dT%H:%M:%S')}\n",
        encoding="utf-8",
    )
    os.replace(path, files_dir / name)
    return f"trashed to {files_dir / name}"


def _dev_of(p: Path) -> int:
    for candidate in (p, *p.parents):
        if candidate.exists():
            return candidate.stat().st_dev
    return -1


# --- Windows -------------------------------------------------------------

def _windows_recycle(path: Path) -> str:
    try:
        from send2trash import send2trash            # type: ignore
    except ImportError as e:
        raise TrashError(
            "the Recycle Bin needs the 'send2trash' package "
            "(pip install send2trash), or pass --purge to delete outright"
        ) from e
    send2trash(str(path))
    return "recycled"
