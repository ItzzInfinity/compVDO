"""GUI tests, run offscreen. They cover wiring, not pixels.

The point is that the window builds, the table fills and sorts correctly, the
mode/theme logic holds, and the archive warning appears — the things that would
otherwise only be caught by a person clicking around.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

pytest.importorskip("PySide6", reason="GUI extra not installed")

from PySide6.QtCore import Qt                                  # noqa: E402
from PySide6.QtWidgets import QApplication                     # noqa: E402

from compvdo.gui.app import DARK, LIGHT, stylesheet            # noqa: E402
from compvdo.gui.main_window import (                          # noqa: E402
    COL_CHECK, COL_SAVING, COL_SIZE, MainWindow, NumericItem,
)
from compvdo.model import ScanEntry                            # noqa: E402

from .conftest import make_info                                # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    app = QApplication.instance() or QApplication([])
    yield app


@pytest.fixture
def window(qapp, tmp_path, monkeypatch):
    # Keep the test off the real settings file.
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    w = MainWindow()
    # A child of a hidden window reports isVisible() == False no matter what
    # setVisible() was called with, so the window has to be shown (offscreen)
    # before visibility assertions mean anything.
    w.show()
    yield w
    w.close()


# --- theme ----------------------------------------------------------------

def test_both_palettes_define_the_same_tokens():
    assert set(LIGHT) == set(DARK)


@pytest.mark.parametrize("dark", [True, False])
def test_stylesheet_has_no_unsubstituted_tokens(dark):
    # '@primary_hover' must not be half-eaten by '@primary'; the substitution
    # goes longest-name-first for exactly this reason.
    assert "@" not in stylesheet(dark)


# --- table ----------------------------------------------------------------

def test_numeric_items_sort_by_value_not_by_text():
    # '9.5 MB' must not sort after '10.2 MB'.
    small, large = NumericItem("9.5 MB", 9.5e6), NumericItem("10.2 MB", 10.2e6)
    assert small < large


def test_table_fills_and_marks_only_worthwhile_files(window):
    worth = make_info(path=Path("/v/big.mp4"), vbitrate=40_000_000)
    lean = make_info(path=Path("/v/lean.mp4"), vbitrate=700_000)
    window._entries = [ScanEntry(info=worth, bpp=worth.bpp, est_saving=1, rank=0),
                       ScanEntry(info=lean, bpp=lean.bpp, est_saving=0, rank=1)]
    window._refill_table()

    assert window.table.rowCount() == 2
    states = {window.table.item(r, COL_CHECK).data(Qt.ItemDataRole.UserRole):
              window.table.item(r, COL_CHECK).checkState()
              for r in range(2)}
    assert states["/v/big.mp4"] == Qt.CheckState.Checked
    assert states["/v/lean.mp4"] == Qt.CheckState.Unchecked


def test_select_none_and_select_all(window):
    i = make_info(vbitrate=40_000_000)
    window._entries = [ScanEntry(info=i, bpp=i.bpp, est_saving=1, rank=0)]
    window._refill_table()
    window._set_all_checked(False)
    assert window._checked_paths() == []
    window._set_all_checked(True)
    assert window._checked_paths() == [str(i.path)]


def test_compress_button_follows_the_selection(window):
    i = make_info(vbitrate=40_000_000)
    window._entries = [ScanEntry(info=i, bpp=i.bpp, est_saving=1, rank=0)]
    window._refill_table()
    window._set_all_checked(False)
    window._update_buttons()
    assert not window.btn_start.isEnabled()
    window._set_all_checked(True)
    window._update_buttons()
    assert window.btn_start.isEnabled()


def test_sorting_by_size_uses_the_numeric_value(window):
    a = make_info(path=Path("/v/a.mp4"), size=9_500_000, vbitrate=40_000_000)
    b = make_info(path=Path("/v/b.mp4"), size=10_200_000, vbitrate=40_000_000)
    window._entries = [ScanEntry(info=x, bpp=x.bpp, est_saving=1, rank=n)
                       for n, x in enumerate((a, b))]
    window._refill_table()
    window.table.sortItems(COL_SIZE, Qt.SortOrder.AscendingOrder)
    first = window.table.item(0, COL_CHECK).data(Qt.ItemDataRole.UserRole)
    assert first == "/v/a.mp4"


# --- modes ----------------------------------------------------------------

def test_archive_warning_is_hidden_until_archive_is_chosen(window):
    from compvdo.model import MODES
    assert not window.archive_warning.isVisible()
    window.mode.setCurrentIndex(MODES.index("archive"))
    assert window.current_mode() == "archive"
    assert window.archive_warning.isVisible()          # R3.2
    window.mode.setCurrentIndex(MODES.index("medium"))
    assert not window.archive_warning.isVisible()


def test_delete_note_appears_with_the_delete_toggle(window):
    window.delete_original.setChecked(True)
    assert window.delete_note.isVisible()
    window.delete_original.setChecked(False)
    assert not window.delete_note.isVisible()


def test_settings_round_trip_through_the_window(window, tmp_path):
    from compvdo.model import MODES
    from compvdo.settings import load
    window.mode.setCurrentIndex(MODES.index("high"))
    window.hw.setChecked(True)
    window._persist()
    stored = load()["defaults"]
    assert stored["mode"] == "high" and stored["hw"] == "auto"


def test_a_restored_archive_preference_still_warns(qapp, tmp_path, monkeypatch):
    """R3.2 — the warning must survive a restart, not only a mode change.

    currentIndexChanged does not fire when the combo is populated from saved
    settings, so a saved 'archive' preference used to come back silently.
    """
    import json
    config = tmp_path / "config" / "compvdo"
    config.mkdir(parents=True)
    (config / "settings.json").write_text(json.dumps({
        "version": 1, "defaults": {"mode": "archive", "hw": "off",
                                   "delete_original": True},
        "ui": {"theme": "system", "last_folder": None, "sort": "savings"},
    }))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))

    w = MainWindow()
    w.show()
    try:
        assert w.current_mode() == "archive"
        assert w.archive_warning.isVisible()
        assert w.delete_note.isVisible()
    finally:
        w.close()


def test_closing_while_a_worker_runs_does_not_abort(qapp, tmp_path, monkeypatch):
    # Qt aborts the process when a QThread is destroyed while running.
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    videos = tmp_path / "videos"
    videos.mkdir()
    w = MainWindow()
    w.show()
    w._set_folder(videos)
    w.close()          # must not raise or abort
    assert w._scan_worker is None or not w._scan_worker.isRunning()


def test_wrapped_labels_grow_to_fit_their_text(qapp):
    """A word-wrapped QLabel clips to one line unless heightForWidth is on."""
    from compvdo.gui.main_window import wrapped_label
    long_text = "word " * 60
    label = wrapped_label(long_text)
    assert label.sizePolicy().hasHeightForWidth()
    assert label.heightForWidth(200) > label.fontMetrics().height() * 2


def test_the_archive_warning_is_a_growing_label(window):
    assert window.archive_warning.sizePolicy().hasHeightForWidth()


def test_a_wrapped_label_is_given_the_height_it_asks_for(window):
    """Regression: the archive warning was handed 31px when it wanted 112."""
    from compvdo.model import MODES
    window.mode.setCurrentIndex(MODES.index("archive"))
    QApplication.processEvents()
    QApplication.processEvents()
    label = window.archive_warning
    assert label.height() >= label.heightForWidth(label.width())
    assert label.height() > label.fontMetrics().height() * 2, "clipped to one line"


def test_choosing_a_second_folder_replaces_the_first(qapp, tmp_path, monkeypatch):
    """Regression: _start_scan returned early while a scan was in flight, so
    the window kept showing the old folder and Compress ran the wrong files."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    first, second = tmp_path / "one", tmp_path / "two"
    first.mkdir()
    second.mkdir()
    w = MainWindow()
    w.show()
    try:
        w._set_folder(first)
        w._set_folder(second)          # must not be ignored
        if w._scan_worker:
            w._scan_worker.wait(10000)
        assert w._folder == second
        assert w.folder_label.text() == str(second)
    finally:
        w.close()
