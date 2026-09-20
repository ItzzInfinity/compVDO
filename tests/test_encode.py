"""Temp-file discipline (R2.1, R2.4) — no ffmpeg needed."""

from __future__ import annotations

import os
from pathlib import Path

from compvdo.encode import _pid_of_temp, cleanup_stale_temps, temp_path_for


def test_temp_lives_beside_the_destination():
    # Invariant 2: a temp in /tmp would make the final move a non-atomic copy.
    dst = Path("/videos/clip_compressed.mp4")
    tmp = temp_path_for(dst)
    assert tmp.parent == dst.parent and tmp.suffix == dst.suffix
    assert tmp.name.startswith(".compvdo-tmp-")


def test_pid_is_recoverable_from_a_temp_name():
    assert _pid_of_temp(temp_path_for(Path("/v/a.mp4"))) == os.getpid()
    assert _pid_of_temp(Path("/v/.compvdo-tmp-nonsense.mp4")) is None


def test_cleanup_removes_temps_from_dead_processes(tmp_path):
    dead = tmp_path / ".compvdo-tmp-999999.mp4"     # pid far beyond pid_max
    dead.write_bytes(b"leftover")
    assert cleanup_stale_temps(tmp_path) == 1
    assert not dead.exists()


def test_cleanup_spares_a_temp_that_is_still_being_written(tmp_path):
    """The regression that destroyed a running encode.

    A second compvdo instance, or the GUI just scanning the folder, used to
    unlink the temp file of a live job. ffmpeg keeps writing to the unlinked
    inode, so the encode 'succeeds' and the output is simply absent.
    """
    live = tmp_path / f".compvdo-tmp-{os.getppid()}.mp4"
    live.write_bytes(b"in flight")
    assert cleanup_stale_temps(tmp_path) == 0
    assert live.exists()


def test_cleanup_removes_our_own_leftovers(tmp_path):
    mine = tmp_path / f".compvdo-tmp-{os.getpid()}.mp4"
    mine.write_bytes(b"ours")
    assert cleanup_stale_temps(tmp_path) == 1


def test_cleanup_ignores_unrelated_files(tmp_path):
    keeper = tmp_path / "holiday.mp4"
    keeper.write_bytes(b"precious")
    cleanup_stale_temps(tmp_path)
    assert keeper.exists()
