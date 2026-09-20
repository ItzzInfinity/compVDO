"""CPU budgeting.

Owns:   how many cores this application is allowed to use.
Reads:  os.cpu_count(), and the process affinity mask where the OS exposes one.
Writes: nothing.
Runs:   nothing.

x265 at `-preset medium` will take every core it can see and hold them for
minutes. On a 12-core desktop that makes the machine unpleasant to use while a
library is being compressed, so the default is to leave headroom rather than to
win a benchmark.
"""

from __future__ import annotations

import os

# Cores deliberately left to the rest of the system. Two is enough to keep a
# desktop, a browser and this application's own UI thread responsive.
RESERVED_CORES = 2


def total_cores() -> int:
    """Cores this process may actually use.

    `len(os.sched_getaffinity(0))` is the honest answer under taskset, cgroups
    and most container runtimes, where `os.cpu_count()` still reports the whole
    host and would have us oversubscribe a two-core slice by six times.
    """
    try:
        return max(1, len(os.sched_getaffinity(0)))      # Linux
    except AttributeError:
        return max(1, os.cpu_count() or 1)


def budget(override: int | None = None, reserved: int = RESERVED_CORES) -> int:
    """How many threads to hand an encoder. Never zero, never more than we have.

    `override` is the user's explicit choice and wins, still clamped to what
    exists — promising 32 threads on a 4-core box just makes x265 thrash.
    """
    total = total_cores()
    if override is not None and override > 0:
        return max(1, min(int(override), total))
    return max(1, total - max(0, reserved))


def describe(override: int | None = None) -> str:
    """One line for a console or a settings panel."""
    total, used = total_cores(), budget(override)
    if override is not None and override > 0:
        return f"{used} of {total} cores (set explicitly)"
    return f"{used} of {total} cores ({total - used} left for the rest of the system)"
