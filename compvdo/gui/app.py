"""QApplication bootstrap and theming (R12.4).

Colour tokens live here and are substituted into theme.qss at load time, so the
stylesheet holds shape and spacing while this module holds the palette. That
split is what makes following the system light/dark preference a one-line
change rather than a second stylesheet.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QPalette
from PySide6.QtWidgets import QApplication

# Material 3 baseline, indigo-ish primary. Two tonal palettes, same token names.
LIGHT = {
    "surface": "#fdfbff", "on_surface": "#1a1c1e",
    "surface_container": "#f2f3f7", "surface_container_alt": "#eceef3",
    "surface_container_high": "#e7e8ee", "on_surface_variant": "#5a5f66",
    "outline": "#9aa0a6", "outline_variant": "#d7dae0",
    "primary": "#4355b9", "on_primary": "#ffffff",
    "primary_hover": "#3a4aa5", "primary_pressed": "#324091",
    "error": "#ba1a1a", "on_error": "#ffffff", "error_hover": "#a01616",
    "warning": "#ffe082", "on_warning": "#3f2e00",
    "hover": "#e3e6ef", "pressed": "#d5d9e6", "select": "#dde1f4",
    "disabled_bg": "#e6e7eb", "disabled_text": "#a9adb4",
}

DARK = {
    "surface": "#121316", "on_surface": "#e3e2e6",
    "surface_container": "#1c1e22", "surface_container_alt": "#202329",
    "surface_container_high": "#272a30", "on_surface_variant": "#c3c6cf",
    "outline": "#5c6068", "outline_variant": "#33363c",
    "primary": "#b9c3ff", "on_primary": "#12246a",
    "primary_hover": "#c8d0ff", "primary_pressed": "#a6b2f5",
    "error": "#ffb4ab", "on_error": "#690005", "error_hover": "#ffc6bf",
    "warning": "#4a3a00", "on_warning": "#ffdf9a",
    "hover": "#2e3138", "pressed": "#383c44", "select": "#333a52",
    "disabled_bg": "#26282d", "disabled_text": "#6c7077",
}


def system_prefers_dark(app: QApplication) -> bool:
    """Qt 6.5+ reports the platform preference; older Qt needs the palette."""
    try:
        return app.styleHints().colorScheme() == Qt.ColorScheme.Dark
    except AttributeError:
        window = app.palette().color(QPalette.ColorRole.Window)
        return window.lightness() < 128


def stylesheet(dark: bool) -> str:
    tokens = DARK if dark else LIGHT
    qss = (Path(__file__).with_name("theme.qss")).read_text(encoding="utf-8")
    # Longest names first: '@primary_hover' must not be eaten by '@primary'.
    for name in sorted(tokens, key=len, reverse=True):
        qss = qss.replace(f"@{name}", tokens[name])
    return qss


def apply_theme(app: QApplication, preference: str = "system") -> bool:
    dark = {"dark": True, "light": False}.get(
        preference, system_prefers_dark(app))
    app.setStyleSheet(stylesheet(dark))
    return dark


def main(argv: list[str] | None = None) -> int:
    from .main_window import MainWindow

    app = QApplication(argv if argv is not None else sys.argv)
    app.setApplicationName("compVDO")
    app.setApplicationDisplayName("compVDO")
    app.setStyle("Fusion")          # the only style the QSS is tuned against

    window = MainWindow()
    apply_theme(app, window.theme_preference)
    window.resize(1120, 720)
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
