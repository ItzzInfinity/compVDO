"""CPU budgeting — the machine must stay usable while a library compresses."""

from __future__ import annotations

from pathlib import Path

import pytest

from compvdo import cpu
from compvdo.model import JobSpec
from compvdo.plan import build

from .conftest import make_info


def test_default_leaves_headroom(monkeypatch):
    monkeypatch.setattr(cpu, "total_cores", lambda: 12)
    assert cpu.budget() == 10


def test_never_returns_zero_on_a_small_machine(monkeypatch):
    for total in (1, 2, 3):
        monkeypatch.setattr(cpu, "total_cores", lambda t=total: t)
        assert cpu.budget() >= 1


def test_explicit_override_wins(monkeypatch):
    monkeypatch.setattr(cpu, "total_cores", lambda: 12)
    assert cpu.budget(4) == 4


def test_override_is_clamped_to_what_exists(monkeypatch):
    # Promising 32 threads on a 4-core box just makes x265 thrash.
    monkeypatch.setattr(cpu, "total_cores", lambda: 4)
    assert cpu.budget(32) == 4


def test_zero_and_negative_overrides_fall_back_to_the_default(monkeypatch):
    monkeypatch.setattr(cpu, "total_cores", lambda: 12)
    assert cpu.budget(0) == 10
    assert cpu.budget(-1) == 10


def test_affinity_is_preferred_over_cpu_count(monkeypatch):
    """Under taskset or a container, cpu_count() reports the whole host."""
    monkeypatch.setattr("os.sched_getaffinity", lambda _: {0, 1}, raising=False)
    monkeypatch.setattr("os.cpu_count", lambda: 64)
    assert cpu.total_cores() == 2


def test_describe_mentions_the_headroom(monkeypatch):
    monkeypatch.setattr(cpu, "total_cores", lambda: 12)
    assert "2 left" in cpu.describe()
    assert "explicitly" in cpu.describe(4)


# --- the budget actually reaches ffmpeg -----------------------------------

def _plan(caps, cores, mode="medium", ext="mp4"):
    i = make_info()
    dst = i.path.with_name(f"{i.path.stem}_compressed.{ext}")
    return build(JobSpec(src=i, dst=dst, mode=mode), caps,
                 Path(f"/videos/.compvdo-tmp-1.{ext}"), cores)


def test_threads_flag_is_passed_to_ffmpeg(caps):
    p = _plan(caps, 4)
    assert p.threads == 4
    assert p.argv[p.argv.index("-threads") + 1] == "4"


def test_x265_pool_is_capped_too(caps):
    # -threads alone does not bound x265: it runs a private pool sized from the
    # machine and ignores it. Without pools= the cap is cosmetic.
    params = [a for a in _plan(caps, 3).argv if "pools=" in a]
    assert params and "pools=3" in params[0]


def test_ffv1_archive_also_gets_a_thread_cap(caps):
    p = _plan(caps, 5, mode="archive", ext="mkv")
    assert p.encoder == "ffv1" and p.argv.count("-threads") >= 1
    assert p.threads == 5


def test_default_plan_uses_the_budget_not_every_core(monkeypatch, caps):
    monkeypatch.setattr(cpu, "total_cores", lambda: 8)
    assert _plan(caps, None).threads == 6


# --- speed controls --------------------------------------------------------

def test_preset_reaches_the_encoder(caps):
    from compvdo.plan import build
    i = make_info()
    spec = JobSpec(src=i, dst=i.path.with_name("clip_compressed.mp4"))
    argv = build(spec, caps, Path("/videos/.compvdo-tmp-1.mp4"), None, "fast").argv
    assert argv[argv.index("-preset") + 1] == "fast"


def test_unknown_preset_is_refused(caps):
    from compvdo.plan import PlanError, build
    i = make_info()
    spec = JobSpec(src=i, dst=i.path.with_name("clip_compressed.mp4"))
    with pytest.raises(PlanError, match="unknown preset"):
        build(spec, caps, Path("/videos/.compvdo-tmp-1.mp4"), None, "blisteringly-fast")


def test_hardware_gets_its_own_quantiser_not_the_crf(caps):
    """Measured: feeding the CRF ladder to VAAPI gave a file 219% of its source."""
    from compvdo.plan import build, hardware_qp
    i = make_info()
    spec = JobSpec(src=i, dst=i.path.with_name("clip_compressed.mp4"), hw="auto")
    p = build(spec, caps, Path("/videos/.compvdo-tmp-1.mp4"))
    assert p.encoder.endswith("_vaapi")
    qp = int(p.argv[p.argv.index("-qp") + 1])
    assert qp == hardware_qp(p.crf) and qp > p.crf


def test_hardware_quantiser_is_capped():
    from compvdo.plan import hardware_qp
    assert hardware_qp(40) <= 42
    assert hardware_qp(None) > 0
