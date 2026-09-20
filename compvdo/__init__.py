"""compvdo — local, offline video re-compression.

The package is a library first: `compvdo.cli` and `compvdo.gui` are both thin
callers over the same core. Nothing under `compvdo/` outside `gui/` may import
a UI toolkit or call `print()`.
"""

__version__ = "0.1.0"
