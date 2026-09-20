"""The encoder / container / audio / naming matrix — no ffmpeg required."""

from dataclasses import replace
from pathlib import Path

import pytest

from compvdo.model import Caps, JobSpec, output_container
from compvdo.plan import (
    AUDIO_CHOICES, AUDIO_DEFAULT, AUDIO_MIN_KBPS, PlanError, build,
    choose_encoder, estimate, is_compressed_output, output_path, rank,
    resolve_audio, resolve_crf,
)

from .conftest import make_info


def plan_for(info, caps, mode="medium", hw="off", crf=None, ext=None, audio="keep"):
    ext = ext or output_container(info.container)
    dst = info.path.with_name(f"{info.path.stem}_compressed.{ext}")
    spec = JobSpec(src=info, dst=dst, mode=mode, hw=hw, crf=crf, audio=audio)
    return build(spec, caps, Path(f"/videos/.compvdo-tmp-1.{ext}"))


def bitrate_of(plan):
    """The -b:a value in the argv, or None when there isn't one."""
    return plan.argv[plan.argv.index("-b:a") + 1] if "-b:a" in plan.argv else None


# --- the ladder (R3) -------------------------------------------------------

@pytest.mark.parametrize("mode,expected", [("low", 28), ("medium", 24), ("high", 20)])
def test_hevc_ladder(info, caps, mode, expected):
    assert plan_for(info, caps, mode).crf == expected


def test_archive_has_no_crf_and_warns(info, caps):
    p = plan_for(info, caps, "archive", ext="mkv")
    assert p.encoder == "ffv1" and p.crf is None
    assert any("LARGER" in n for n in p.notes), "R3.2 growth warning is missing"


def test_ladder_shifts_for_av1_but_names_are_stable(caps):
    # R3.3 — a CRF is not comparable across codecs.
    assert resolve_crf("libsvtav1", "medium", None) == 32
    assert resolve_crf("libx265", "medium", None) == 24
    assert resolve_crf("libx264", "medium", None) == 22


def test_explicit_crf_overrides_the_ladder(info, caps):
    assert plan_for(info, caps, "high", crf=31).crf == 31          # R3.4


def test_crf_override_is_ignored_for_archive(info, caps):
    assert plan_for(info, caps, "archive", crf=31, ext="mkv").crf is None


# --- encoder selection (R4) ------------------------------------------------

def test_software_is_the_default_even_when_hardware_exists(info, caps):
    assert plan_for(info, caps, hw="off").encoder == "libx265"      # R4.2


def test_hw_auto_prefers_vaapi_when_a_device_node_exists(info, caps):
    assert plan_for(info, caps, hw="auto").encoder == "hevc_vaapi"


def test_hw_auto_ignores_vaapi_without_a_device(info, caps):
    # ffmpeg lists hevc_nvenc/hevc_qsv on machines that have neither, so a
    # listed encoder proves nothing. This is the regression that guards it.
    blind = replace(caps, vaapi_device=None)
    assert choose_encoder(blind, "medium", "auto") == "hevc_nvenc"


def test_hw_auto_falls_back_to_software_silently(info, caps):
    sw = replace(caps, encoders=frozenset({"libx265"}), vaapi_device=None)
    assert choose_encoder(sw, "medium", "auto") == "libx265"        # R4.1


def test_archive_without_ffv1_is_an_error_not_a_silent_downgrade(caps):
    no_ffv1 = replace(caps, encoders=frozenset({"libx265"}))
    with pytest.raises(PlanError, match="ffv1"):
        choose_encoder(no_ffv1, "archive", "off")


def test_no_encoder_at_all_is_an_error(caps):
    with pytest.raises(PlanError, match="no usable video encoder"):
        choose_encoder(replace(caps, encoders=frozenset()), "medium", "off")


# --- containers and audio (R1.3, R6) ---------------------------------------

@pytest.mark.parametrize("src,out", [
    ("mp4", "mp4"), ("mov", "mp4"), ("avi", "mp4"), ("3gp", "mp4"),
    ("m4v", "mp4"), ("mts", "mp4"), ("mkv", "mkv"), ("webm", "mkv"),
])
def test_output_container_mapping(src, out):
    assert output_container(src) == out


def test_audio_is_copied_when_legal(info, caps):
    p = plan_for(info, caps)
    assert p.audio_action == "copy" and "copy" in p.argv                # R6.1


def test_audio_is_reencoded_when_illegal_in_the_target(caps):
    # FLAC in an MP4 is not valid; it must become AAC and say so.
    i = make_info(acodec="flac")
    p = plan_for(i, caps)
    assert p.audio_action == "aac"
    assert "-b:a" in p.argv and any("R6.2" in n for n in p.notes)


def test_flac_survives_as_copy_into_mkv(caps):
    i = make_info(path=Path("/videos/clip.mkv"), container="mkv", acodec="flac")
    assert plan_for(i, caps).audio_action == "copy"


def test_silent_source_gets_an(caps):
    p = plan_for(make_info(acodec=None), caps)
    assert p.audio_action == "none" and "-an" in p.argv


# --- mp4 specifics ---------------------------------------------------------

def test_hvc1_tag_only_for_mp4(info, caps):
    # Without hvc1, QuickTime and iOS refuse to play HEVC in MP4.
    assert "hvc1" in plan_for(info, caps).argv
    mkv = make_info(path=Path("/videos/c.mkv"), container="mkv")
    assert "hvc1" not in plan_for(mkv, caps).argv


def test_only_one_movflags_option(info, caps):
    # ffmpeg keeps the LAST occurrence of an option; two -movflags means the
    # first one is silently discarded.
    assert plan_for(info, caps).argv.count("-movflags") == 1


def test_progress_is_requested_on_stdout(info, caps):
    argv = plan_for(info, caps).argv
    assert argv[argv.index("-progress") + 1] == "pipe:1"                # R12.3
    assert "-nostats" in argv


def test_vaapi_gets_a_device_and_an_upload_filter(info, caps):
    argv = plan_for(info, caps, hw="auto").argv
    assert "-vaapi_device" in argv
    assert "format=nv12,hwupload" in argv


# --- naming (R1.1, R1.2, R1.4) ---------------------------------------------

def test_output_name_and_placement():
    out = output_path(Path("/videos/holiday.MOV"), exists=lambda p: False)
    assert out == Path("/videos/holiday_compressed.mp4")               # R1.1


def test_collision_never_overwrites():
    taken = {Path("/videos/a_compressed.mp4"), Path("/videos/a_compressed (2).mp4")}
    out = output_path(Path("/videos/a.mp4"), exists=lambda p: p in taken)
    assert out == Path("/videos/a_compressed (3).mp4")                  # R1.2


@pytest.mark.parametrize("name,flag", [
    ("a_compressed.mp4", True), ("a_compressed (2).mp4", True),
    ("a.mp4", False), ("compressed.mp4", False), ("a_compressed (x).mp4", False),
])
def test_recognises_own_output(name, flag):
    assert is_compressed_output(Path("/v") / name) is flag              # R1.4


def test_jobspec_rejects_output_elsewhere(info):
    with pytest.raises(ValueError, match="beside the original"):
        JobSpec(src=info, dst=Path("/tmp/out.mp4"))


def test_jobspec_rejects_unknown_mode(info):
    with pytest.raises(ValueError, match="unknown mode"):
        JobSpec(src=info, dst=Path("/videos/clip_compressed.mp4"), mode="ultra")


# --- ranking (R10.3) -------------------------------------------------------

def test_bpp_arithmetic():
    i = make_info(width=1000, height=1000, fps=10.0, vbitrate=1_000_000)
    assert i.bpp == pytest.approx(0.1)


def test_overbitrated_h264_is_worth_compressing(info):
    bpp, saving = estimate(info)
    assert bpp > 0.03 and saving > 0


def test_already_lean_file_promises_nothing():
    lean = make_info(vbitrate=1_000_000)          # ~0.016 bpp
    assert estimate(lean)[1] == 0


def test_already_hevc_is_judged_more_leniently():
    h264 = make_info(vcodec="h264", vbitrate=3_000_000)
    hevc = make_info(vcodec="hevc", vbitrate=3_000_000)
    assert estimate(h264)[1] > estimate(hevc)[1]


def test_unknown_bitrate_never_invents_a_saving():
    assert estimate(make_info(vbitrate=0, duration=0))[1] == 0          # R10.4


def test_rank_orders_by_estimated_saving():
    big = make_info(path=Path("/v/big.mp4"), size=500_000_000, vbitrate=40_000_000)
    small = make_info(path=Path("/v/small.mp4"), size=5_000_000, vbitrate=13_000_000, duration=3)
    lean = make_info(path=Path("/v/lean.mp4"), vbitrate=800_000)
    ordered = rank([lean, small, big])
    assert [e.info.path.name for e in ordered][0] == "big.mp4"
    assert ordered[-1].info.path.name == "lean.mp4" and ordered[-1].est_saving == 0
    assert [e.rank for e in ordered] == [0, 1, 2]


# --- re-run guard ----------------------------------------------------------

def test_existing_output_is_found_for_either_container():
    from compvdo.plan import existing_output
    have = {Path("/v/a_compressed.mkv")}
    assert existing_output(Path("/v/a.mkv"), exists=lambda p: p in have) == Path("/v/a_compressed.mkv")


def test_existing_output_is_none_when_absent():
    from compvdo.plan import existing_output
    assert existing_output(Path("/v/a.mp4"), exists=lambda p: False) is None


def test_plan_jobs_skips_already_compressed_sources(info, tmp_path, monkeypatch):
    # Guards the regression where re-running on a folder produced a second
    # set of outputs named "... (2).mp4".
    from compvdo.batch import plan_jobs
    src = tmp_path / "clip.mp4"
    src.write_bytes(b"x")
    (tmp_path / "clip_compressed.mp4").write_bytes(b"x")
    i = make_info(path=src)
    specs, already = plan_jobs([i])
    assert specs == [] and already and already[0][1].name == "clip_compressed.mp4"
    specs, already = plan_jobs([i], again=True)
    assert len(specs) == 1 and already == []


def test_archive_forces_mkv_because_ffv1_is_illegal_in_mp4():
    from compvdo.plan import target_container
    assert target_container("mp4", "archive") == "mkv"
    assert target_container("mp4", "archive", container="mp4") == "mkv"
    assert target_container("mp4", "medium") == "mp4"
    assert target_container("mp4", "medium", container="mkv") == "mkv"


def test_existing_output_only_checks_the_container_being_produced():
    from compvdo.plan import existing_output
    have = {Path("/v/a_compressed.mp4")}
    ex = lambda p: p in have
    assert existing_output(Path("/v/a.mp4"), "mp4", exists=ex) is not None
    # An archive run targets .mkv, so the existing .mp4 must not block it.
    assert existing_output(Path("/v/a.mp4"), "mkv", exists=ex) is None


def test_estimate_range_brackets_the_point_estimate():
    from compvdo.plan import estimate, estimate_range
    i = make_info()
    low, high = estimate_range(i)
    assert low < estimate(i)[1] < high


def test_estimate_range_is_zero_when_nothing_is_promised():
    from compvdo.plan import estimate_range
    assert estimate_range(make_info(vbitrate=800_000)) == (0, 0)


def test_estimate_range_never_promises_more_than_the_file_holds():
    from compvdo.plan import estimate_range
    i = make_info(vbitrate=200_000_000)
    assert estimate_range(i)[1] <= i.size


def test_scan_stops_when_the_caller_says_so(tmp_path):
    from compvdo.scan import scan
    from compvdo.model import Caps
    for n in range(5):
        (tmp_path / f"c{n}.mp4").write_bytes(b"x")
    caps = Caps(ffmpeg=Path("/bin/true"), ffprobe=Path("/bin/true"), ffmpeg_version="x")
    seen = []
    entries, skipped = scan(tmp_path, caps, on_file=lambda n, t, p: seen.append(p),
                            should_continue=lambda: len(seen) < 2)
    assert len(seen) == 2, "scan ignored should_continue"


# --- opt-in audio re-encode (R6.3 - R6.6) ----------------------------------

def test_the_default_is_still_a_stream_copy(info, caps):
    # R6.1 must survive Phase 2c: no flag, no re-encode, no -b:a at all.
    p = plan_for(info, caps)                       # no audio= argument
    assert p.audio_action == "copy"
    assert bitrate_of(p) is None
    assert AUDIO_DEFAULT == "keep" and AUDIO_CHOICES[0] == "keep"


@pytest.mark.parametrize("choice,expected", [("192k", "192k"), ("160k", "160k"),
                                             ("128k", "128k")])
def test_each_fixed_option_sets_that_bitrate(info, caps, choice, expected):
    p = plan_for(info, caps, audio=choice)         # R6.3
    assert p.audio_action == "aac"
    assert bitrate_of(p) == expected
    assert "-c:a" in p.argv and p.argv[p.argv.index("-c:a") + 1] == "aac"


def test_reencode_leaves_channels_and_sample_rate_alone(info, caps):
    # R6.4 — bitrate only. -ac / -ar would resample and downmix behind the
    # user's back, which is not what 'compress the audio' means.
    p = plan_for(info, caps, audio="128k")
    assert "-ac" not in p.argv and "-ar" not in p.argv


def test_below_the_floor_is_clamped_and_said_out_loud(info, caps):
    p = plan_for(info, caps, audio="96k")          # R6.5
    assert bitrate_of(p) == f"{AUDIO_MIN_KBPS}k"
    assert any("128" in n and "floor" in n for n in p.notes), \
        "the clamp must be reported, not applied silently"


def test_the_floor_lives_in_exactly_one_constant():
    assert AUDIO_MIN_KBPS == 128
    assert resolve_audio("64k")[0] == AUDIO_MIN_KBPS
    assert resolve_audio("8")[0] == AUDIO_MIN_KBPS


def test_resolve_audio_is_pure_and_keeps_legal_values(caps):
    assert resolve_audio("keep") == (None, None)
    assert resolve_audio(None) == (None, None)
    assert resolve_audio("192k") == (192, None)
    assert resolve_audio(160) == (160, None)


def test_an_unknown_audio_option_is_an_error(info, caps):
    with pytest.raises(PlanError):
        plan_for(info, caps, audio="best")


def test_a_silent_source_stays_silent_whatever_was_asked(caps):
    # R6.6 — the option is a no-op, and says so rather than pretending.
    p = plan_for(make_info(acodec=None), caps, audio="128k")
    assert p.audio_action == "none"
    assert "-an" in p.argv and bitrate_of(p) is None
    assert any("no audio" in n for n in p.notes)


def test_forced_reencode_still_happens_without_the_flag(caps):
    # R6.2 is untouched: an illegal codec is re-encoded at 192k by default.
    p = plan_for(make_info(acodec="flac"), caps)
    assert p.audio_action == "aac" and bitrate_of(p) == "192k"
    assert any("R6.2" in n for n in p.notes)


def test_a_chosen_bitrate_wins_over_the_forced_default(caps):
    # R6.2 + R6.3 compose: one re-encode, at the bitrate the user picked,
    # and the container reason is still explained exactly once.
    p = plan_for(make_info(acodec="flac"), caps, audio="128k")
    assert p.audio_action == "aac" and bitrate_of(p) == "128k"
    assert p.argv.count("-c:a") == 1
    assert len([n for n in p.notes if "R6.2" in n]) == 1


def test_a_legal_codec_in_mkv_can_still_be_re_encoded_on_request(caps):
    # flac is legal in mkv, so R6.2 does not fire - but R6.3 still should.
    i = make_info(path=Path("/videos/clip.mkv"), container="mkv", acodec="flac")
    assert plan_for(i, caps).audio_action == "copy"
    p = plan_for(i, caps, audio="160k")
    assert p.audio_action == "aac" and bitrate_of(p) == "160k"
    assert not any("R6.2" in n for n in p.notes)
