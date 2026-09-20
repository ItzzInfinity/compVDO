from pathlib import Path

import pytest

from compvdo.model import Caps, MediaInfo


@pytest.fixture
def caps() -> Caps:
    """A fully-capable fake ffmpeg. Individual tests narrow it down."""
    return Caps(
        ffmpeg=Path("/usr/bin/ffmpeg"),
        ffprobe=Path("/usr/bin/ffprobe"),
        ffmpeg_version="6.0",
        encoders=frozenset({"libx265", "libx264", "ffv1", "hevc_vaapi", "hevc_nvenc"}),
        vaapi_device=Path("/dev/dri/renderD128"),
    )


def make_info(**over) -> MediaInfo:
    base = dict(
        path=Path("/videos/clip.mp4"), size=100_000_000, duration=60.0,
        width=1920, height=1080, rotation=0, fps=30.0,
        vcodec="h264", acodec="aac", vbitrate=13_000_000,
        mtime=1_700_000_000.0, container="mp4",
    )
    base.update(over)
    return MediaInfo(**base)


@pytest.fixture
def info():
    return make_info()
