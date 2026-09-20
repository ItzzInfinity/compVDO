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


# --- dev_guide.md norms ----------------------------------------------------

def test_console_uses_the_standard_prefix_vocabulary(window):
    """dev_guide.md §11 — one prefix vocabulary across the application."""
    window._log("INFO", "hello")
    window._log("WARN", "careful")
    window._log("ERR", "broken")
    window._log("INFO", "")            # empty must not emit a bare prefix
    text = window.log.toPlainText()
    assert text.splitlines() == ["[INFO] hello", "[WARN] careful", "[ERR] broken"]


def test_one_busy_flag_governs_every_control(window):
    """dev_guide.md §7.5 — one flag, one method, idempotent."""
    i = make_info(vbitrate=40_000_000)
    window._entries = [ScanEntry(info=i, bpp=i.bpp, est_saving=1, rank=0)]
    window._refill_table()

    window._set_busy(True)
    assert window.btn_cancel.isEnabled()
    for widget in (window.btn_start, window.btn_open, window.btn_rescan,
                   window.mode, window.hw, window.delete_original,
                   window.btn_preview):
        assert not widget.isEnabled(), f"{widget.objectName() or widget} left live while busy"

    window._set_busy(True)              # idempotent
    window._set_busy(False)
    assert not window.btn_cancel.isEnabled()
    assert window.btn_open.isEnabled() and window.mode.isEnabled()


def test_cancel_does_nothing_when_not_busy(window):
    window._set_busy(False)
    window._cancel()                    # must not raise
    assert not window._busy


def test_deleting_originals_asks_first_and_defaults_to_cancel(window, monkeypatch):
    """dev_guide.md §12 — confirm with specifics, safe button focused."""
    from PySide6.QtWidgets import QMessageBox

    seen = {}

    def fake_exec(self):
        seen["text"] = self.text()
        seen["detail"] = self.informativeText()
        seen["default"] = self.defaultButton()
        seen["cancel_btn"] = self.button(QMessageBox.StandardButton.Cancel)
        return QMessageBox.StandardButton.Cancel

    monkeypatch.setattr(QMessageBox, "exec", fake_exec)
    infos = [make_info(path=Path(f"/v/clip{n}.mp4")) for n in range(3)]

    assert window._confirm_delete(infos) is False        # declining means no
    assert "3 original file(s)" in seen["text"]
    assert "clip0.mp4" in seen["detail"]                 # names the targets
    assert "trash" in seen["detail"].lower()
    assert seen["default"] is seen["cancel_btn"], "safe button is not the default"


def test_delete_confirmation_summarises_a_long_list(window, monkeypatch):
    from PySide6.QtWidgets import QMessageBox
    seen = {}
    monkeypatch.setattr(QMessageBox, "exec",
                        lambda self: (seen.update(detail=self.informativeText()),
                                      QMessageBox.StandardButton.Yes)[1])
    infos = [make_info(path=Path(f"/v/c{n}.mp4")) for n in range(30)]
    assert window._confirm_delete(infos) is True
    assert "and 18 more" in seen["detail"]               # 12 shown + 18


# --- audio control (R6.3 / R6.5) -------------------------------------------

def test_audio_defaults_to_keep_and_hides_its_note(window):
    assert window.current_audio() == "keep"                    # R6.1
    assert not window.audio_note.isVisible()


def test_choosing_a_bitrate_persists_and_shows_the_note(window):
    from compvdo.settings import load
    window.audio.setCurrentIndex(3)                            # 'AAC 128 kbps'
    assert window.current_audio() == "128k"
    assert window.audio_note.isVisible()
    assert load()["defaults"]["audio"] == "128k"
    assert any("128k" in line for line in window.log.toPlainText().splitlines())


def test_the_audio_combo_only_offers_legal_values(window):
    from compvdo.plan import AUDIO_CHOICES, AUDIO_MIN_KBPS
    assert window.audio.count() == len(AUDIO_CHOICES)
    for choice in AUDIO_CHOICES[1:]:
        assert int(choice.rstrip("k")) >= AUDIO_MIN_KBPS


def test_a_stored_value_below_the_floor_is_clamped_and_logged(qapp, tmp_path, monkeypatch):
    # R6.5 — a hand-edited settings.json must not smuggle 64k past the floor.
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "cfg"))
    from compvdo import settings
    data = settings.load()
    data["defaults"]["audio"] = "64k"
    settings.save(data)
    w = MainWindow()
    try:
        assert w.current_audio() == "128k"
        assert "floor" in w.log.toPlainText()
    finally:
        w.close()


def test_the_audio_combo_is_disabled_while_busy(window):
    window._set_busy(True)                                     # dev_guide §7.5
    assert not window.audio.isEnabled()
    window._set_busy(False)
    assert window.audio.isEnabled()


# --- theme control (R12.4) -------------------------------------------------

def test_theme_defaults_to_following_the_system(window):
    assert window.current_theme() == "system"


@pytest.mark.parametrize("index,expected", [(0, "system"), (1, "light"), (2, "dark")])
def test_theme_choice_persists(window, index, expected):
    from compvdo.settings import load
    window.theme.setCurrentIndex(index)
    assert window.current_theme() == expected
    assert load()["ui"]["theme"] == expected


def test_choosing_light_restyles_the_running_app(qapp, window):
    """A setting that needs a restart to take effect feels broken."""
    window.theme.setCurrentIndex(2)           # dark
    dark_sheet = qapp.styleSheet()
    window.theme.setCurrentIndex(1)           # light
    assert qapp.styleSheet() != dark_sheet
    assert "@" not in qapp.styleSheet()


def test_theme_combo_offers_exactly_the_three_modes(window):
    assert [window.theme.itemText(i) for i in range(window.theme.count())] == [
        "Follow system", "Light", "Dark",
    ]
