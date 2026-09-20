"""User defaults and the capability cache (docs/data-model.md)."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

from .model import Caps
from .probe import detect_caps

SCHEMA_VERSION = 1
CAPS_MAX_AGE = 30 * 24 * 3600          # re-probe monthly

DEFAULTS = {
    "version": SCHEMA_VERSION,
    "defaults": {"mode": "medium", "hw": "off", "delete_original": False,
                 "jobs": 1, "container": None, "cores": None,
                 # R6.3 — 'keep' stream-copies, which is R6.1's default.
                 "audio": "keep"},
    "ui": {"theme": "system", "last_folder": None, "sort": "savings"},
    "caps": None,
}


def config_path() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "compvdo" / "settings.json"


def load() -> dict:
    path = config_path()
    data = dict(DEFAULTS)
    if path.exists():
        try:
            on_disk = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            # A corrupt config must never stop the tool from running.
            return data
        if isinstance(on_disk, dict):
            data = _merge(data, on_disk)
    return data


def _merge(base: dict, over: dict) -> dict:
    """Deep-merge, keeping keys we do not know about.

    A newer version's settings must survive being opened by an older one, so
    unknown keys are preserved rather than dropped on the next write.
    """
    out = dict(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def save(data: dict) -> None:
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    os.replace(tmp, path)          # never leave a half-written config


# --- capability cache (R4.3) ---------------------------------------------

def cached_caps(force: bool = False) -> Caps:
    """Caps from the cache when it is fresh, otherwise a fresh probe.

    A stale cache must never cause a failure, so anything unexpected in the
    stored blob simply triggers a re-probe.
    """
    data = load()
    blob = data.get("caps")
    if not force and isinstance(blob, dict):
        try:
            fresh = (time.time() - float(blob["probed_at"])) < CAPS_MAX_AGE
            ffmpeg, ffprobe = Path(blob["ffmpeg"]), Path(blob["ffprobe"])
            if fresh and ffmpeg.exists() and ffprobe.exists():
                return Caps(
                    ffmpeg=ffmpeg, ffprobe=ffprobe,
                    ffmpeg_version=blob["ffmpeg_version"],
                    encoders=frozenset(blob["encoders"]),
                    vaapi_device=Path(blob["vaapi_device"]) if blob.get("vaapi_device") else None,
                    probed_at=float(blob["probed_at"]),
                )
        except (KeyError, TypeError, ValueError):
            pass

    caps = detect_caps()
    data["caps"] = {
        "ffmpeg": str(caps.ffmpeg), "ffprobe": str(caps.ffprobe),
        "ffmpeg_version": caps.ffmpeg_version, "encoders": sorted(caps.encoders),
        "vaapi_device": str(caps.vaapi_device) if caps.vaapi_device else None,
        "probed_at": caps.probed_at,
    }
    try:
        save(data)
    except OSError:
        pass          # an unwritable config is not a reason to fail the run
    return caps
