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


def test_empty_and_invalid_utf8_documents_fail_closed() -> None:
    parser = LightweightParser()

    empty = parser.parse_bytes(b"", ".txt")
    invalid = parser.parse_bytes(b"\xff\xfe", ".txt")

    assert empty.status == "FAILED"
    assert empty.warnings == ["document is empty"]
    assert invalid.status == "FAILED"
    assert invalid.warnings == ["document is not valid UTF-8"]


def test_html_is_converted_to_sections_and_tables() -> None:
    parsed = LightweightParser().parse_bytes(
        b"<h1>Results</h1><p>Stable.</p><table><tr><th>Name</th><th>Value</th></tr>"
        b"<tr><td>A</td><td>B</td></tr></table>",
        ".html",
    )

    assert parsed.status == "SUCCEEDED"
    assert parsed.sections[0].heading == "Results"
    assert parsed.tables[0].rows == [{"Name": "A", "Value": "B"}]
