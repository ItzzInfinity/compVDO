"""compVDO main window.

Owns:   the single application window. There is no tab bar; this application has
        one surface (dev_guide.md §3 applies, §4's tab machinery does not).
Reads:  the user's settings file, via compvdo.settings; media metadata via
        compvdo.scan / compvdo.probe.
Writes: the user's settings file. Nothing else — all output is written by
        compvdo.encode, beside the original (R1.1).
Runs:   ffmpeg and ffprobe, always through compvdo.encode / compvdo.probe and
        always off the UI thread (dev_guide.md §5 Pattern C, §9).

Widgets and wiring only. Any code here that builds an ffmpeg argument is in the
wrong file; that belongs in compvdo.plan.
"""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QAbstractItemView, QCheckBox, QComboBox, QFileDialog, QFrame, QHBoxLayout,
    QHeaderView, QLabel, QMainWindow, QMessageBox, QPlainTextEdit, QProgressBar,
    QPushButton, QSizePolicy, QSplitter, QTableWidget, QTableWidgetItem,
    QVBoxLayout, QWidget,
)

from ..batch import plan_jobs, prepare
from ..cli import clock, human
from ..model import MODES, JobResult, ScanEntry
from ..plan import AUDIO_CHOICES, AUDIO_DEFAULT, resolve_audio
from ..probe import FFmpegMissing
from ..scan import sort_entries
from ..cpu import describe as describe_cores
from ..settings import cached_caps, load, save
from .worker import EncodeWorker, PreviewWorker, ScanWorker

COLUMNS = ("", "Name", "Size", "Length", "Codec", "bits/px", "Est. saving", "Status")
COL_CHECK, COL_NAME, COL_SIZE, COL_LEN, COL_CODEC, COL_BPP, COL_SAVING, COL_STATUS = range(8)

# Header index -> the sort key scan.sort_entries understands (R10.2).
SORT_KEYS = {COL_NAME: "name", COL_SIZE: "size", COL_SAVING: "savings"}


class WrappingLabel(QLabel):
    """A QLabel that actually grows to fit its wrapped text.

    Two defaults conspire to clip a word-wrapped label to one line in a vertical
    layout: the size policy does not ask for heightForWidth, and a vertical
    policy of MinimumExpanding lets the layout shrink the label to its
    single-line minimumSizeHint whenever something else (a stretch, a sibling)
    wants the space. Measured here: the label asked for 112px and was given 31.

    So: heightForWidth on, vertical policy Minimum, and a minimum height
    recomputed on every resize — because heightForWidth depends on the width,
    which the splitter can change at any moment.
    """

    def __init__(self, text: str, object_name: str = "Subtle") -> None:
        super().__init__(text)
        self.setObjectName(object_name)
        self.setWordWrap(True)
        policy = self.sizePolicy()
        policy.setHeightForWidth(True)
        policy.setVerticalPolicy(QSizePolicy.Policy.Minimum)
        self.setSizePolicy(policy)

    def setText(self, text: str) -> None:
        super().setText(text)
        self._fit()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._fit()

    def _fit(self) -> None:
        if self.width() > 0:
            self.setMinimumHeight(self.heightForWidth(self.width()))


def wrapped_label(text: str, object_name: str = "Subtle") -> QLabel:
    return WrappingLabel(text, object_name)


class NumericItem(QTableWidgetItem):
    """Sorts on a stored number while displaying a formatted string.

    Without this, '9.5 MB' sorts after '10.2 MB' because Qt compares the text.
    """

    def __init__(self, text: str, value: float) -> None:
        super().__init__(text)
        self._value = value
        self.setFlags(self.flags() & ~Qt.ItemFlag.ItemIsEditable)

    def __lt__(self, other: object) -> bool:
        if isinstance(other, NumericItem):
            return self._value < other._value
        return super().__lt__(other)


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("compVDO")

        self._settings = load()
        self.theme_preference = self._settings["ui"].get("theme", "system")
        self._entries: list[ScanEntry] = []
        self._results: dict[str, JobResult] = {}
        self._scan_worker: ScanWorker | None = None
        self._encode_worker: EncodeWorker | None = None
        self._preview_worker: PreviewWorker | None = None
        self._folder: Path | None = None
        self._caps = None
        self._busy = False              # dev_guide.md §7.5: exactly one flag
        self._audio_clamp: str | None = None    # set while building, logged after

        self._build()
        self._load_caps()

        # Reflect the *restored* settings, not just future changes: the mode
        # combo is populated from settings.json, and currentIndexChanged does
        # not fire for that. Without this, starting the app with a saved
        # 'archive' preference shows no warning at all (R3.2).
        self._sync_mode_dependent_widgets()
        if self._audio_clamp:                    # R6.5, never silent
            self._log("WARN", self._audio_clamp)

        last = self._settings["ui"].get("last_folder")
        if last and Path(last).is_dir():
            self._set_folder(Path(last))

    # -- construction -----------------------------------------------------

    def _build(self) -> None:
        root = QWidget()
        outer = QVBoxLayout(root)
        outer.setContentsMargins(16, 14, 16, 12)
        outer.setSpacing(12)

        outer.addLayout(self._build_header())

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.addWidget(self._build_table())
        splitter.addWidget(self._build_side_panel())
        splitter.setStretchFactor(0, 1)
        splitter.setSizes([780, 320])
        outer.addWidget(splitter, 1)

        outer.addWidget(self._build_footer())
        self.setCentralWidget(root)
        self.statusBar().showMessage("Choose a folder to begin.")

    def _build_header(self) -> QHBoxLayout:
        row = QHBoxLayout()
        row.setSpacing(10)

        title = QLabel("compVDO")
        title.setObjectName("Heading")
        row.addWidget(title)

        self.folder_label = QLabel("no folder chosen")
        self.folder_label.setObjectName("Subtle")
        self.folder_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        row.addWidget(self.folder_label, 1)

        self.btn_open = QPushButton("Choose folder…")
        self.btn_open.clicked.connect(self._choose_folder)
        row.addWidget(self.btn_open)

        self.btn_rescan = QPushButton("Rescan")
        self.btn_rescan.setObjectName("Text")
        self.btn_rescan.setEnabled(False)
        self.btn_rescan.clicked.connect(lambda: self._folder and self._start_scan(self._folder))
        row.addWidget(self.btn_rescan)
        return row

    def _build_table(self) -> QWidget:
        wrap = QWidget()
        box = QVBoxLayout(wrap)
        box.setContentsMargins(0, 0, 0, 0)
        box.setSpacing(8)

        bar = QHBoxLayout()
        self.summary = QLabel("")
        self.summary.setObjectName("Subtle")
        bar.addWidget(self.summary, 1)
        for text, slot in (("Select all", lambda: self._set_all_checked(True)),
                           ("Select none", lambda: self._set_all_checked(False)),
                           ("Select suggested", self._select_suggested)):
            b = QPushButton(text)
            b.setObjectName("Text")
            b.clicked.connect(slot)
            bar.addWidget(b)
        box.addLayout(bar)

        self.table = QTableWidget(0, len(COLUMNS))
        self.table.setHorizontalHeaderLabels(COLUMNS)
        self.table.verticalHeader().setVisible(False)
        self.table.setAlternatingRowColors(True)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setSortingEnabled(True)
        self.table.setShowGrid(False)

        header = self.table.horizontalHeader()
        header.setSectionResizeMode(COL_NAME, QHeaderView.ResizeMode.Stretch)
        for col in (COL_CHECK, COL_SIZE, COL_LEN, COL_CODEC, COL_BPP, COL_SAVING, COL_STATUS):
            header.setSectionResizeMode(col, QHeaderView.ResizeMode.ResizeToContents)
        self.table.itemChanged.connect(self._on_item_changed)
        self.table.itemSelectionChanged.connect(self._update_buttons)
        box.addWidget(self.table, 1)

        note = QLabel("“Est. saving” is an estimate from bits-per-pixel, not a promise.")
        note.setObjectName("Subtle")
        box.addWidget(note)
        return wrap

    def _build_side_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("Card")
        box = QVBoxLayout(panel)
        box.setContentsMargins(16, 16, 16, 16)
        box.setSpacing(10)

        heading = QLabel("Settings")
        heading.setObjectName("Heading")
        box.addWidget(heading)

        box.addWidget(QLabel("Quality"))
        self.mode = QComboBox()
        self.mode.addItems([
            "low — smallest files, soft detail",
            "medium — recommended",
            "high — visually lossless",
            "archive — bit-exact, LARGER file",
        ])
        self.mode.setCurrentIndex(MODES.index(self._settings["defaults"].get("mode", "medium")))
        self.mode.currentIndexChanged.connect(self._on_mode_changed)
        box.addWidget(self.mode)

        self.archive_warning = wrapped_label(
            "Archive mode is mathematically lossless. Your videos are already "
            "compressed, so the output will almost certainly be LARGER than the "
            "original — often several times. Choose “high” for smaller files "
            "with no visible loss.", "Warning")
        self.archive_warning.setVisible(False)
        box.addWidget(self.archive_warning)

        self.hw = QCheckBox("Use hardware encoding (faster, larger files)")
        self.hw.setChecked(self._settings["defaults"].get("hw") == "auto")
        self.hw.stateChanged.connect(self._persist)
        box.addWidget(self.hw)

        # R6.3 — audio is opt-in. A fixed list, not a number box: the floor is
        # then structural rather than something to validate after the fact.
        box.addWidget(QLabel("Audio"))
        self.audio = QComboBox()
        self.audio.addItems([
            "keep original (no re-encode)",
            "AAC 192 kbps",
            "AAC 160 kbps",
            "AAC 128 kbps — smallest allowed",
        ])
        self.audio.setCurrentIndex(self._audio_index())
        self.audio.currentIndexChanged.connect(self._on_audio_changed)
        box.addWidget(self.audio)

        self.audio_note = wrapped_label(
            "Re-encoding audio saves a few MB per hour and costs a little "
            "quality. Never goes below 128 kbps.")
        self.audio_note.setVisible(False)
        box.addWidget(self.audio_note)

        self.delete_original = QCheckBox("Delete originals after compressing")
        self.delete_original.setChecked(bool(self._settings["defaults"].get("delete_original")))
        self.delete_original.stateChanged.connect(self._on_delete_toggled)
        box.addWidget(self.delete_original)

        self.delete_note = wrapped_label(
            "Originals go to the system trash, and only after the new file "
            "passes verification.")
        self.delete_note.setVisible(self.delete_original.isChecked())
        box.addWidget(self.delete_note)

        box.addSpacing(6)
        self.btn_preview = QPushButton("Preview selected file…")
        self.btn_preview.setEnabled(False)
        self.btn_preview.clicked.connect(self._start_preview)
        box.addWidget(self.btn_preview)

        box.addWidget(wrapped_label(
            "Encodes a 10-second sample into a temp folder so you can judge the "
            "setting without waiting for the whole file."))

        box.addStretch(1)

        self.caps_label = wrapped_label("")
        box.addWidget(self.caps_label)
        return panel

    def _build_footer(self) -> QWidget:
        card = QFrame()
        card.setObjectName("Card")
        box = QVBoxLayout(card)
        box.setContentsMargins(16, 12, 16, 12)
        box.setSpacing(8)

        self.current_label = QLabel("Idle")
        box.addWidget(self.current_label)

        self.file_bar = QProgressBar()
        self.file_bar.setRange(0, 1000)
        box.addWidget(self.file_bar)

        row = QHBoxLayout()
        self.overall_label = QLabel("")
        self.overall_label.setObjectName("Subtle")
        row.addWidget(self.overall_label, 1)

        self.btn_cancel = QPushButton("Cancel")
        self.btn_cancel.setObjectName("Danger")
        self.btn_cancel.setEnabled(False)
        self.btn_cancel.clicked.connect(self._cancel)
        row.addWidget(self.btn_cancel)

        self.btn_start = QPushButton("Compress")
        self.btn_start.setObjectName("Filled")
        self.btn_start.setEnabled(False)
        self.btn_start.clicked.connect(self._start_encode)
        row.addWidget(self.btn_start)
        box.addLayout(row)

        self.overall_bar = QProgressBar()
        self.overall_bar.setObjectName("Overall")
        self.overall_bar.setRange(0, 1000)
        box.addWidget(self.overall_bar)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumHeight(96)
        box.addWidget(self.log)
        return card

    # -- helpers ----------------------------------------------------------

    def _load_caps(self) -> None:
        try:
            self._caps = cached_caps()
        except FFmpegMissing as e:
            self.caps_label.setText("ffmpeg not found")
            QMessageBox.critical(self, "ffmpeg is required", str(e))
            self.btn_open.setEnabled(False)
            return
        hw = "hardware encoding available" if self._caps.vaapi_device else "software encoding"
        self.caps_label.setText(f"ffmpeg {self._caps.ffmpeg_version} · {hw} · "
                                f"{describe_cores(self._cores())}")

    def _log(self, tag: str, text: str) -> None:
        """The single place anything reaches the console (dev_guide.md §11).

        Vocabulary: TX a command we sent, RX a line back, INFO normal progress,
        WARN skipped or degraded but continuing, ERR the operation failed.
        """
        if text:
            self.log.appendPlainText(f"[{tag}] {text.rstrip()}")

    def current_mode(self) -> str:
        return MODES[self.mode.currentIndex()]

    def current_audio(self) -> str:
        return AUDIO_CHOICES[self.audio.currentIndex()]

    def _audio_index(self) -> int:
        """Stored value -> combo row, clamping anything below the floor (R6.5).

        A settings.json written by hand (or by an older build) can hold a
        bitrate we no longer offer; it is pulled up to 128k rather than
        silently honoured or crashing the window.
        """
        stored = self._settings["defaults"].get("audio", AUDIO_DEFAULT)
        if stored in AUDIO_CHOICES:
            return AUDIO_CHOICES.index(stored)
        try:
            kbps, note = resolve_audio(stored)
        except Exception:
            return 0
        if kbps is None:
            return 0
        if note:
            # The log widget does not exist yet at this point in _build().
            self._audio_clamp = note
        label = f"{kbps}k"
        return AUDIO_CHOICES.index(label) if label in AUDIO_CHOICES else len(AUDIO_CHOICES) - 1

    def _persist(self) -> None:
        self._settings["defaults"]["mode"] = self.current_mode()
        self._settings["defaults"]["hw"] = "auto" if self.hw.isChecked() else "off"
        self._settings["defaults"]["audio"] = self.current_audio()
        self._settings["defaults"]["delete_original"] = self.delete_original.isChecked()
        self._settings["ui"]["last_folder"] = str(self._folder) if self._folder else None
        save(self._settings)

    def _sync_mode_dependent_widgets(self) -> None:
        self.archive_warning.setVisible(self.current_mode() == "archive")   # R3.2
        self.delete_note.setVisible(self.delete_original.isChecked())
        self.audio_note.setVisible(self.current_audio() != AUDIO_DEFAULT)

    def _on_audio_changed(self) -> None:
        self._sync_mode_dependent_widgets()
        self._persist()
        choice = self.current_audio()
        self._log("INFO", "audio will be stream-copied unchanged (R6.1)"
                  if choice == AUDIO_DEFAULT
                  else f"audio will be re-encoded to AAC {choice} (R6.3)")

    def _on_mode_changed(self) -> None:
        self._sync_mode_dependent_widgets()
        self._persist()
        if self._entries:
            self._refill_table()

    def _on_delete_toggled(self) -> None:
        self.delete_note.setVisible(self.delete_original.isChecked())
        self._persist()

    # -- scanning ---------------------------------------------------------

    def _choose_folder(self) -> None:
        start = str(self._folder or Path.home())
        chosen = QFileDialog.getExistingDirectory(self, "Choose a folder of videos", start)
        if chosen:
            self._set_folder(Path(chosen))

    def _set_folder(self, folder: Path) -> None:
        self._folder = folder
        self.folder_label.setText(str(folder))
        self.btn_rescan.setEnabled(True)
        self._persist()
        self._start_scan(folder)

    def _start_scan(self, folder: Path) -> None:
        # Returning early here meant that choosing a second folder while the
        # first was still being scanned did nothing at all: the user picks a
        # folder, the window keeps showing the old one, and the next Compress
        # runs against files they never selected. Stop the old scan instead.
        if self._scan_worker is not None and self._scan_worker.isRunning():
            self._scan_worker.requestInterruption()
            if not self._scan_worker.wait(3000):
                self._scan_worker.terminate()
                self._scan_worker.wait(1000)
        if (n := prepare(folder)):
            self._log("INFO", f"cleared {n} temp file(s) from an interrupted run")
        self.table.setRowCount(0)
        self._entries, self._results = [], {}
        self.summary.setText("scanning…")
        self.btn_start.setEnabled(False)

        self._scan_worker = ScanWorker(folder, self._caps, self.current_mode())
        self._scan_worker.progress.connect(
            lambda n, total, name: self.statusBar().showMessage(f"scanning {n}/{total}: {name}"))
        self._scan_worker.finished_ok.connect(self._on_scan_done)
        self._scan_worker.failed.connect(
            lambda msg: QMessageBox.warning(self, "Scan failed", msg))
        self._scan_worker.start()

    def _on_scan_done(self, entries: list, skipped: list) -> None:
        self._entries = entries
        self._refill_table()
        for path, why in skipped:
            self._log("WARN", f"skipped {path.name}: {why}")
        self.statusBar().showMessage(
            f"{len(entries)} video(s) found" + (f", {len(skipped)} unreadable" if skipped else ""))

    def _refill_table(self) -> None:
        from ..plan import estimate

        # The ranking depends on the mode, so it is recomputed when the mode
        # changes rather than frozen at scan time.
        mode = self.current_mode()
        refreshed = []
        for e in self._entries:
            bpp, saving = estimate(e.info, mode)
            refreshed.append(ScanEntry(info=e.info, bpp=bpp, est_saving=saving, rank=e.rank))
        self._entries = sort_entries(
            [ScanEntry(info=e.info, bpp=e.bpp, est_saving=e.est_saving, rank=n)
             for n, e in enumerate(sorted(refreshed, key=lambda x: (-x.est_saving, -x.info.size)))],
            "savings")

        self.table.setSortingEnabled(False)
        self.table.setRowCount(len(self._entries))
        for row, entry in enumerate(self._entries):
            i = entry.info
            check = QTableWidgetItem()
            check.setFlags(Qt.ItemFlag.ItemIsUserCheckable | Qt.ItemFlag.ItemIsEnabled)
            check.setCheckState(Qt.CheckState.Checked if entry.est_saving > 0
                                else Qt.CheckState.Unchecked)
            check.setData(Qt.ItemDataRole.UserRole, str(i.path))
            self.table.setItem(row, COL_CHECK, check)

            name = QTableWidgetItem(i.path.name)
            name.setToolTip(str(i.path))
            name.setFlags(name.flags() & ~Qt.ItemFlag.ItemIsEditable)
            self.table.setItem(row, COL_NAME, name)

            self.table.setItem(row, COL_SIZE, NumericItem(human(i.size), i.size))
            self.table.setItem(row, COL_LEN, NumericItem(clock(i.duration), i.duration))
            codec = QTableWidgetItem(i.vcodec)
            codec.setFlags(codec.flags() & ~Qt.ItemFlag.ItemIsEditable)
            self.table.setItem(row, COL_CODEC, codec)
            self.table.setItem(row, COL_BPP, NumericItem(f"{entry.bpp:.3f}", entry.bpp))
            self.table.setItem(row, COL_SAVING, NumericItem(
                human(entry.est_saving) if entry.est_saving else "—", entry.est_saving))

            result = self._results.get(str(i.path))
            status = QTableWidgetItem(_status_text(result))
            status.setFlags(status.flags() & ~Qt.ItemFlag.ItemIsEditable)
            self.table.setItem(row, COL_STATUS, status)

        self.table.setSortingEnabled(True)
        self._update_summary()

    def _update_summary(self) -> None:
        worth = [e for e in self._entries if e.est_saving > 0]
        total = sum(e.est_saving for e in worth)
        self.summary.setText(
            f"{len(self._entries)} videos · {len(worth)} worth compressing · "
            f"~{human(total)} estimated saving")
        self._update_buttons()

    def _checked_paths(self) -> list[str]:
        out = []
        for row in range(self.table.rowCount()):
            item = self.table.item(row, COL_CHECK)
            if item and item.checkState() == Qt.CheckState.Checked:
                out.append(item.data(Qt.ItemDataRole.UserRole))
        return out

    def _set_all_checked(self, checked: bool) -> None:
        state = Qt.CheckState.Checked if checked else Qt.CheckState.Unchecked
        for row in range(self.table.rowCount()):
            if item := self.table.item(row, COL_CHECK):
                item.setCheckState(state)

    def _select_suggested(self) -> None:
        worthwhile = {str(e.info.path) for e in self._entries if e.est_saving > 0}
        for row in range(self.table.rowCount()):
            item = self.table.item(row, COL_CHECK)
            if item:
                item.setCheckState(
                    Qt.CheckState.Checked
                    if item.data(Qt.ItemDataRole.UserRole) in worthwhile
                    else Qt.CheckState.Unchecked)

    def _on_item_changed(self, item: QTableWidgetItem) -> None:
        if item.column() == COL_CHECK:
            self._update_buttons()

    def _set_busy(self, busy: bool) -> None:
        """The single place run/cancel enablement is decided (dev_guide.md §7.5).

        Idempotent, and called from every terminal path including error and
        cancel — a leaked busy flag leaves the window dead until restart.
        """
        self._busy = busy
        self.btn_cancel.setEnabled(busy)
        self.btn_open.setEnabled(not busy)
        self.btn_rescan.setEnabled(not busy and self._folder is not None)
        self.mode.setEnabled(not busy)
        self.hw.setEnabled(not busy)
        self.audio.setEnabled(not busy)
        self.delete_original.setEnabled(not busy)
        self._update_buttons()

    def _update_buttons(self) -> None:
        self.btn_start.setEnabled(bool(self._checked_paths()) and not self._busy)
        self.btn_preview.setEnabled(self._selected_entry() is not None and not self._busy)

    def _selected_entry(self) -> ScanEntry | None:
        rows = {i.row() for i in self.table.selectedIndexes()}
        if len(rows) != 1:
            return None
        item = self.table.item(rows.pop(), COL_CHECK)
        if not item:
            return None
        path = item.data(Qt.ItemDataRole.UserRole)
        return next((e for e in self._entries if str(e.info.path) == path), None)

    # -- encoding ---------------------------------------------------------

    def _start_encode(self) -> None:
        chosen = set(self._checked_paths())
        infos = [e.info for e in self._entries if str(e.info.path) in chosen]
        if not infos:
            return

        mode = self.current_mode()
        if mode == "archive":          # R3.2 - warn before, not after
            answer = QMessageBox.warning(
                self, "Archive mode makes bigger files",
                "Archive mode is mathematically lossless.\n\n"
                "Your videos are already compressed, so the results will almost "
                "certainly be LARGER than the originals — often several times "
                "larger.\n\nUse “high” instead for smaller files with no visible "
                "loss.\n\nContinue with archive mode?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel,
                QMessageBox.StandardButton.Cancel)
            if answer != QMessageBox.StandardButton.Yes:
                return

        delete = self.delete_original.isChecked()
        if delete and not self._confirm_delete(infos):
            return

        audio = self.current_audio()
        specs, already = plan_jobs(
            infos, mode=mode, hw="auto" if self.hw.isChecked() else "off",
            delete_original=delete)
        if audio != AUDIO_DEFAULT:
            specs = [replace(s, audio=audio) for s in specs]
        for src, prev in already:
            self._log("WARN", f"skipped {src.name}: {prev.name} already exists")
        if not specs:
            QMessageBox.information(self, "Nothing to do",
                                    "Every selected file already has a compressed version.")
            return

        self._encode_worker = EncodeWorker(specs, self._caps, resume_in=self._folder,
                                           cores=self._cores())
        self._encode_worker.file_progress.connect(self._on_file_progress)
        self._encode_worker.file_done.connect(self._on_file_done)
        self._encode_worker.all_done.connect(self._on_all_done)
        self._encode_worker.failed.connect(
            lambda msg: QMessageBox.critical(self, "Compression failed", msg))
        self._encode_worker.finished.connect(self._update_buttons)

        self._total_jobs = len(specs)
        self._set_busy(True)
        self._log("TX", f"ffmpeg × {len(specs)} at mode={mode}, "
                        f"hw={'auto' if self.hw.isChecked() else 'off'}, "
                        f"audio={audio}, {describe_cores(self._cores())}")
        self._encode_worker.start()

    def _cores(self) -> int | None:
        value = self._settings["defaults"].get("cores")
        return int(value) if value else None

    def _confirm_delete(self, infos: list) -> bool:
        """dev_guide.md §12 — name the targets, count them, say it is final.

        The safe button is the default, so a stray Return key cannot delete
        anyone's holiday footage.
        """
        shown = [i.path.name for i in infos[:12]]
        listing = "\n".join(f"  • {n}" for n in shown)
        if len(infos) > len(shown):
            listing += f"\n  … and {len(infos) - len(shown)} more"
        total = sum(i.size for i in infos)

        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Warning)
        box.setWindowTitle("Delete the originals?")
        box.setText(
            f"After compressing, these {len(infos)} original file(s) "
            f"({human(total)}) will be moved to the system trash:")
        box.setInformativeText(
            f"{listing}\n\n"
            "Each original is deleted only after its new file passes "
            "verification, and never if the new file is larger. "
            "Recovering them afterwards means digging in the trash.")
        box.setStandardButtons(QMessageBox.StandardButton.Cancel
                               | QMessageBox.StandardButton.Yes)
        box.setDefaultButton(QMessageBox.StandardButton.Cancel)
        box.button(QMessageBox.StandardButton.Yes).setText("Compress and delete")
        if box.exec() != QMessageBox.StandardButton.Yes:
            self._log("INFO", "delete cancelled; nothing was changed")
            return False
        self._log("WARN", f"originals will be trashed after verification "
                          f"({len(infos)} file(s), {human(total)})")
        return True

    def _on_file_progress(self, idx: int, total: int, name: str, fraction: float) -> None:
        self.current_label.setText(f"[{idx}/{total}] {name}")
        self.file_bar.setValue(int(fraction * 1000))
        # Overall is (finished files + this file's fraction) / total, so it
        # tracks reality rather than counting whole files (R12.3).
        self.overall_bar.setValue(int(((idx - 1 + fraction) / total) * 1000))
        self.overall_label.setText(f"{idx - 1} of {total} done")

    def _on_file_done(self, result: JobResult) -> None:
        self._results[str(result.spec.src.path)] = result
        ratio = f"{result.ratio * 100:.0f}%" if result.ratio else "—"
        tag = {"ok": "INFO", "skipped": "INFO", "grew": "WARN",
               "cancelled": "WARN", "failed": "ERR"}[result.status]
        self._log(tag, f"{result.status.upper():9} {result.spec.src.path.name}  "
                       f"{human(result.spec.src.size)} → {human(result.dst_size)} ({ratio})"
                       + (f"  · {result.message}" if result.message else ""))
        for row in range(self.table.rowCount()):
            item = self.table.item(row, COL_CHECK)
            if item and item.data(Qt.ItemDataRole.UserRole) == str(result.spec.src.path):
                self.table.item(row, COL_STATUS).setText(_status_text(result))
                break

    def _on_all_done(self, results: list) -> None:
        self._set_busy(False)
        self.file_bar.setValue(0)
        self.overall_bar.setValue(1000)
        ok = [r for r in results if r.status == "ok"]
        grew = [r for r in results if r.status == "grew"]
        failed = [r for r in results if r.status == "failed"]
        saved = sum(r.saved or 0 for r in ok)
        self.current_label.setText(
            f"Done — {len(ok)} compressed, {human(saved)} saved"
            + (f", {len(grew)} grew" if grew else "")
            + (f", {len(failed)} failed" if failed else ""))
        self.overall_label.setText("")

        # dev_guide.md §11: a run that does not say where its output went has
        # failed the operator.
        if ok:
            where = ok[0].spec.dst.parent
            self._log("INFO", f"{len(ok)} file(s) written to {where}")
        if grew:
            self._log("WARN", f"{len(grew)} output(s) were larger than the "
                              f"original and were kept; no original was deleted")
        for r in failed:
            self._log("ERR", f"{r.spec.src.path.name}: "
                             f"{r.message.splitlines()[0] if r.message else 'unknown failure'}")

    def _cancel(self) -> None:
        if not self._busy:
            return
        if self._encode_worker:
            self._log("INFO", "cancel requested")
            self._encode_worker.cancel()
            self.current_label.setText("cancelling…")
            self.btn_cancel.setEnabled(False)

    # -- preview ----------------------------------------------------------

    def _start_preview(self) -> None:
        entry = self._selected_entry()
        if not entry:
            return
        self.btn_preview.setEnabled(False)
        self._log("TX", f"preview sample of {entry.info.path.name}")
        self.current_label.setText(f"preview: {entry.info.path.name}")
        self._preview_worker = PreviewWorker(
            entry.info.path, self._caps, self.current_mode(),
            "auto" if self.hw.isChecked() else "off", cores=self._cores())
        self._preview_worker.progress.connect(lambda f: self.file_bar.setValue(int(f * 1000)))
        self._preview_worker.finished_ok.connect(self._on_preview_done)
        self._preview_worker.failed.connect(self._on_preview_failed)
        self._preview_worker.finished.connect(self._update_buttons)
        self._preview_worker.start()

    def _on_preview_done(self, sample: Path, out: Path, ratio: float) -> None:
        self.file_bar.setValue(0)
        self.current_label.setText("Idle")
        box = QMessageBox(self)
        box.setWindowTitle("Preview ready")
        box.setText(f"A {ratio * 100:.0f}% sample was written.\n\n"
                    f"Original sample:\n{sample}\n\nCompressed:\n{out}\n\n"
                    "Nothing was written next to your original.")
        box.setStandardButtons(QMessageBox.StandardButton.Open | QMessageBox.StandardButton.Close)
        box.button(QMessageBox.StandardButton.Open).setText("Open folder")
        if box.exec() == QMessageBox.StandardButton.Open:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(out.parent)))

    def _on_preview_failed(self, message: str) -> None:
        self.file_bar.setValue(0)
        self.current_label.setText("Idle")
        QMessageBox.warning(self, "Preview failed", message)

    # -- shutdown ---------------------------------------------------------

    def _stop_workers(self) -> None:
        """Qt aborts the process if a QThread is destroyed while running, so
        every worker has to be stopped and joined before the window goes."""
        for worker in (self._scan_worker, self._preview_worker, self._encode_worker):
            if worker is not None and worker.isRunning():
                if hasattr(worker, "cancel"):
                    worker.cancel()
                worker.requestInterruption()
                if not worker.wait(5000):
                    worker.terminate()
                    worker.wait(2000)

    def closeEvent(self, event) -> None:
        """Never leave an ffmpeg running, or a temp file, behind us."""
        if self._encode_worker and self._encode_worker.isRunning():
            answer = QMessageBox.question(
                self, "Still compressing",
                "A compression job is still running. Stop it and quit?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No)
            if answer != QMessageBox.StandardButton.Yes:
                event.ignore()
                return
        self._stop_workers()
        self._persist()
        event.accept()


def _status_text(result: JobResult | None) -> str:
    if result is None:
        return ""
    return {
        "ok": "compressed", "grew": "GREW — kept original",
        "failed": "failed", "cancelled": "cancelled", "skipped": "already done",
    }.get(result.status, result.status)
