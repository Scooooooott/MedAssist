from pathlib import Path

from parser_svc.core import LightweightParser


def test_markdown_sections_keep_headings(tmp_path: Path) -> None:
    path = tmp_path / "note.md"
    path.write_text("# Subjective\nCough.\n# Objective\nStable.", encoding="utf-8")

    parsed = LightweightParser().parse_path(path)

    assert parsed.status == "SUCCEEDED"
    assert [section.heading for section in parsed.sections] == ["Subjective", "Objective"]


def test_unsupported_format_fails_readably(tmp_path: Path) -> None:
    path = tmp_path / "broken.pdf"
    path.write_text("not really pdf", encoding="utf-8")

    parsed = LightweightParser().parse_path(path)

    assert parsed.status == "FAILED"
    assert "unsupported format" in parsed.warnings[0]
