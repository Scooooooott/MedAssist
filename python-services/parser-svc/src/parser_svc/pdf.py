from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Protocol

from parser_svc.core import LightweightParser, ParsedDocument
from parser_svc.settings import ParserSettings


class PdfBackendError(RuntimeError):
    """A PDF backend could not produce a parseable document."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class PdfBackend(Protocol):
    def parse(self, payload: bytes, source: str) -> ParsedDocument:
        """Parse PDF bytes without exposing the bytes to the RPC contract."""


class DoclingPdfBackend:
    """Docling adapter with lazy imports so text parsing stays lightweight."""

    def parse(self, payload: bytes, source: str) -> ParsedDocument:
        try:
            from docling.document_converter import DocumentConverter
        except ImportError as exc:
            raise PdfBackendError(
                "PDF_BACKEND_UNAVAILABLE",
                "the configured Docling PDF backend is not installed",
            ) from exc

        try:
            with tempfile.TemporaryDirectory(prefix="medassist-parser-") as directory:
                pdf_path = Path(directory) / "document.pdf"
                pdf_path.write_bytes(payload)
                result = DocumentConverter().convert(str(pdf_path))
                document = getattr(result, "document", None)
                if document is None:
                    raise PdfBackendError("PDF_PARSE_FAILED", "Docling returned no document")
                markdown = document.export_to_markdown()
        except PdfBackendError:
            raise
        except Exception as exc:
            raise PdfBackendError("PDF_PARSE_FAILED", "Docling could not parse the PDF") from exc

        if not isinstance(markdown, str) or not markdown.strip():
            raise PdfBackendError("PDF_PARSE_FAILED", "Docling returned no parseable content")
        parsed = LightweightParser().parse_bytes(markdown.encode("utf-8"), ".md", source)
        if parsed.status == "FAILED" or not parsed.sections and not parsed.tables:
            raise PdfBackendError("PDF_PARSE_FAILED", "Docling returned no parseable content")
        parsed.metadata["parser_backend"] = "docling"
        return parsed


def build_pdf_backend(settings: ParserSettings) -> PdfBackend | None:
    backend = settings.pdf_backend.strip().lower()
    if backend in {"", "none", "disabled"}:
        return None
    if backend == "docling":
        return DoclingPdfBackend()
    raise PdfBackendError("PDF_BACKEND_UNSUPPORTED", f"unsupported PDF backend: {backend}")
