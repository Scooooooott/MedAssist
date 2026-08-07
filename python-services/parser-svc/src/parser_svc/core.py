from __future__ import annotations

import html
import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path


@dataclass
class ParsedTable:
    caption: str
    headers: list[str]
    rows: list[dict[str, str]]
    start: int
    end: int


@dataclass
class ParsedSection:
    path: str
    heading: str
    level: int
    text: str
    start: int
    end: int
    children: list["ParsedSection"] = field(default_factory=list)


@dataclass
class ParsedDocument:
    sections: list[ParsedSection]
    tables: list[ParsedTable]
    metadata: dict[str, str]
    warnings: list[str]
    status: str


class _HtmlToMarkdown(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.lines: list[str] = []
        self._table_rows: list[list[str]] | None = None
        self._cell: list[str] | None = None
        self._row: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            self.lines.append("#" * int(tag[1]) + " ")
        elif tag == "table":
            self._table_rows = []
        elif tag == "tr" and self._table_rows is not None:
            self._row = []
        elif tag in {"th", "td"} and self._row is not None:
            self._cell = []
        elif tag in {"p", "div", "br", "li"}:
            self.lines.append("\n")

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in {"th", "td"} and self._cell is not None and self._row is not None:
            self._row.append("".join(self._cell).strip())
            self._cell = None
        elif tag == "tr" and self._row is not None and self._table_rows is not None:
            self._table_rows.append(self._row)
            self._row = None
        elif tag == "table" and self._table_rows is not None:
            rows = self._table_rows
            self._table_rows = None
            if rows:
                self.lines.append("\n| " + " | ".join(rows[0]) + " |\n")
                self.lines.append("| " + " | ".join("---" for _ in rows[0]) + " |\n")
                for row in rows[1:]:
                    self.lines.append("| " + " | ".join(row) + " |\n")
        elif tag in {"h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "li"}:
            self.lines.append("\n")

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)
        else:
            self.lines.append(data)

    def render(self) -> str:
        return html.unescape("".join(self.lines))


class LightweightParser:
    supported_suffixes = {".txt", ".md", ".markdown", ".html", ".htm"}

    def parse_path(self, path: Path) -> ParsedDocument:
        try:
            return self.parse_bytes(path.read_bytes(), path.suffix, str(path))
        except OSError as exc:
            return ParsedDocument([], [], {"source": str(path)}, [f"read failed: {exc}"], "FAILED")

    def parse_bytes(self, payload: bytes, suffix: str, source: str = "") -> ParsedDocument:
        suffix = suffix.lower()
        if suffix not in self.supported_suffixes:
            return ParsedDocument([], [], {"source": source}, [f"unsupported format: {suffix}"], "FAILED")
        if not payload:
            return ParsedDocument([], [], {"source": source}, ["document is empty"], "FAILED")
        try:
            text = payload.decode("utf-8")
        except UnicodeDecodeError:
            return ParsedDocument([], [], {"source": source}, ["document is not valid UTF-8"], "FAILED")
        if suffix in {".html", ".htm"}:
            text = _HtmlToMarkdown()
            text.feed(payload.decode("utf-8"))
            text = text.render()
        tables = self._tables(text)
        sections = self._sections(text)
        if not sections and not tables:
            return ParsedDocument([], [], {"source": source}, ["document contains no parseable content"], "FAILED")
        return ParsedDocument(
            sections,
            tables,
            {"source": source, "language": "en"},
            [],
            "SUCCEEDED",
        )

    def _tables(self, text: str) -> list[ParsedTable]:
        lines = text.splitlines(keepends=True)
        tables: list[ParsedTable] = []
        offset = 0
        index = 0
        while index + 1 < len(lines):
            header = lines[index].strip()
            separator = lines[index + 1].strip()
            if "|" not in header or not re.fullmatch(r"\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?", separator):
                offset += len(lines[index])
                index += 1
                continue
            headers = [part.strip() for part in header.strip("|").split("|")]
            start = offset
            cursor = offset + len(lines[index]) + len(lines[index + 1])
            rows: list[dict[str, str]] = []
            row_index = index + 2
            while row_index < len(lines) and "|" in lines[row_index]:
                values = [part.strip() for part in lines[row_index].strip().strip("|").split("|")]
                rows.append({name: values[pos] if pos < len(values) else "" for pos, name in enumerate(headers)})
                cursor += len(lines[row_index])
                row_index += 1
            tables.append(ParsedTable(f"table-{len(tables) + 1}", headers, rows, start, cursor))
            while index < row_index:
                offset += len(lines[index])
                index += 1
        return tables

    def _sections(self, text: str) -> list[ParsedSection]:
        matches = list(re.finditer(r"^(#{1,6})\s+(.+?)\s*$", text, re.MULTILINE))
        if not matches:
            body = text.strip()
            return [ParsedSection("1", "Document", 1, body, 0, len(text))] if body else []
        nodes: list[ParsedSection] = []
        roots: list[ParsedSection] = []
        stack: list[ParsedSection] = []
        for match in matches:
            level = len(match.group(1))
            node = ParsedSection("", match.group(2).strip(), level, "", match.start(), len(text))
            while stack and stack[-1].level >= level:
                stack.pop()
            if stack:
                stack[-1].children.append(node)
            else:
                roots.append(node)
            stack.append(node)
            nodes.append(node)
        for index, node in enumerate(nodes):
            boundary = len(text)
            for following in nodes[index + 1 :]:
                if following.level <= node.level:
                    boundary = following.start
                    break
            first_child = node.children[0].start if node.children else boundary
            line_end = text.find("\n", node.start)
            content_start = len(text) if line_end < 0 else line_end + 1
            node.text = text[content_start:first_child].strip()
            node.end = boundary
        self._assign_paths(roots, "")
        return roots

    def _assign_paths(self, sections: list[ParsedSection], parent: str) -> None:
        for index, section in enumerate(sections, start=1):
            section.path = f"{parent}.{index}".lstrip(".")
            self._assign_paths(section.children, section.path)
