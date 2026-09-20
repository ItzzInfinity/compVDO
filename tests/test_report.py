"""The metadata report: structure, comparison pairing, and safe escaping."""

from __future__ import annotations

from pathlib import Path

import pytest

from compvdo.report import _flatten, _table, find_compressed


def test_flatten_walks_nested_ffprobe_json():
    rows: list[tuple[str, str]] = []
    _flatten("", {"a": 1, "b": {"c": "x"}, "d": [{"e": 2}]}, rows)
    assert ("`a`", "1") in rows
    assert ("`b.c`", "x") in rows
    assert ("`d[0].e`", "2") in rows


def test_flatten_collapses_multiline_values():
    # Display matrices arrive as multi-line strings and would break the table.
    rows: list[tuple[str, str]] = []
    _flatten("m", "line one\nline two", rows)
    assert rows == [("`m`", "line one line two")]


def test_table_escapes_pipes_so_rows_do_not_break():
    out = _table([("k", "a|b")])
    assert r"a\|b" in "\n".join(out)


def test_table_handles_no_rows():
    assert _table([])[0] == "_none_"


def test_find_compressed_locates_a_moved_output(tmp_path):
    src = tmp_path / "clip.mp4"
    src.write_bytes(b"x")
    out_dir = tmp_path / "output"
    out_dir.mkdir()
    target = out_dir / "clip_compressed.mp4"
    target.write_bytes(b"y")
    assert find_compressed(src, [tmp_path, out_dir]) == target


def test_find_compressed_returns_none_when_absent(tmp_path):
    src = tmp_path / "clip.mp4"
    src.write_bytes(b"x")
    assert find_compressed(src, [tmp_path]) is None


def test_find_compressed_does_not_match_a_different_stem(tmp_path):
    (tmp_path / "other_compressed.mp4").write_bytes(b"y")
    src = tmp_path / "clip.mp4"
    src.write_bytes(b"x")
    assert find_compressed(src, [tmp_path]) is None


@pytest.mark.parametrize("ext", ["mp4", "mkv"])
def test_find_compressed_accepts_either_container(tmp_path, ext):
    src = tmp_path / "clip.mov"
    src.write_bytes(b"x")
    target = tmp_path / f"clip_compressed.{ext}"
    target.write_bytes(b"y")
    assert find_compressed(src, [tmp_path]) == target
