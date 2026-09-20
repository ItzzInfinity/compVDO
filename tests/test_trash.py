"""R2.3 — a 'deleted' original must stay somewhere the user can actually find."""

from pathlib import Path

import pytest

from compvdo import trash


def test_snap_sandboxed_xdg_data_home_is_ignored(monkeypatch, tmp_path):
    # A terminal inside the VS Code snap exports a per-snap XDG_DATA_HOME.
    # Honouring it hides trashed videos from the user's file manager.
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "snap" / "code" / "263" / ".local" / "share"))
    assert trash.data_home() == tmp_path / ".local" / "share"


def test_ordinary_xdg_data_home_is_respected(monkeypatch, tmp_path):
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "custom"))
    assert trash.data_home() == tmp_path / "custom"


def test_unset_xdg_data_home_falls_back_to_the_spec_default(monkeypatch, tmp_path):
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    assert trash.data_home() == tmp_path / ".local" / "share"


def test_manual_trash_writes_a_restorable_info_record(monkeypatch, tmp_path):
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    victim = tmp_path / "clip.mp4"
    victim.write_bytes(b"data")

    trash._manual_xdg_trash(victim)

    files = tmp_path / ".local" / "share" / "Trash" / "files"
    info = tmp_path / ".local" / "share" / "Trash" / "info"
    assert (files / "clip.mp4").read_bytes() == b"data"
    assert not victim.exists()
    record = (info / "clip.mp4.trashinfo").read_text()
    assert "[Trash Info]" in record and "clip.mp4" in record


def test_trashing_twice_does_not_clobber_the_first_copy(monkeypatch, tmp_path):
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    files = tmp_path / ".local" / "share" / "Trash" / "files"
    for content in (b"first", b"second"):
        v = tmp_path / "clip.mp4"
        v.write_bytes(content)
        trash._manual_xdg_trash(v)
    assert {p.read_bytes() for p in files.iterdir()} == {b"first", b"second"}


def test_missing_file_is_an_error_not_a_silent_success(tmp_path):
    with pytest.raises(trash.TrashError):
        trash.send(tmp_path / "nope.mp4")
