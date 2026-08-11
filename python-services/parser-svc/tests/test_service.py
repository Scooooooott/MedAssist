from __future__ import annotations

import io
import time
from dataclasses import dataclass, field
from typing import Any, cast

import grpc
import pytest
from medassist.contracts.v1 import parser_pb2
from parser_svc.core import LightweightParser, ParsedDocument, ParsedSection
from parser_svc.object_store import ObjectStoreError, S3ObjectStore, StoredObject
from parser_svc.pdf import PdfBackendError, build_pdf_backend
from parser_svc.service import ParserServiceServicer
from parser_svc.settings import ParserSettings


@dataclass
class MemoryReader:
    stored: StoredObject
    calls: list[str] = field(default_factory=list)

    def read(self, storage_uri: str) -> StoredObject:
        self.calls.append(storage_uri)
        return self.stored


class FailingPdfBackend:
    def parse(self, payload: bytes, source: str) -> ParsedDocument:
        del payload, source
        raise PdfBackendError("PDF_PARSE_FAILED", "damaged PDF: no readable pages")


class SlowPdfBackend:
    def parse(self, payload: bytes, source: str) -> ParsedDocument:
        del payload, source
        time.sleep(0.05)
        return ParsedDocument(
            [ParsedSection("1", "Document", 1, "content", 0, 7)],
            [],
            {},
            [],
            "SUCCEEDED",
        )


class PartialParser(LightweightParser):
    def parse_bytes(self, payload: bytes, suffix: str, source: str = "") -> ParsedDocument:
        del payload, suffix, source
        return ParsedDocument(
            [ParsedSection("1", "Document", 1, "partial content", 0, 15)],
            [],
            {},
            ["one block could not be normalized"],
            "PARTIAL",
        )


def request(
    uri: str, *, mime_type: str = "", source_id: str = ""
) -> parser_pb2.ParseDocumentRequest:
    return parser_pb2.ParseDocumentRequest(
        storage_uri=uri,
        mime_type=mime_type,
        source_id=source_id,
    )


def call(
    service: ParserServiceServicer, request_message: parser_pb2.ParseDocumentRequest
) -> parser_pb2.ParseDocumentResponse:
    return service.ParseDocument(request_message, cast(grpc.ServicerContext, object()))


def test_service_reads_object_store_and_maps_document_metadata() -> None:
    reader = MemoryReader(StoredObject(b"# Heading\nText", "text/markdown"))
    service = ParserServiceServicer(reader)

    response = call(
        service,
        request("s3://raw/doc.md", mime_type="text/markdown", source_id="source-1"),
    )

    assert response.parse_status == parser_pb2.PARSE_STATUS_SUCCEEDED
    assert response.ir.sections[0].heading == "Heading"
    assert response.ir.metadata["source_id"] == "source-1"
    assert reader.calls == ["s3://raw/doc.md"]


def test_unsupported_mime_is_a_failed_response_with_readable_warning() -> None:
    service = ParserServiceServicer(MemoryReader(StoredObject(b"data", "")))

    response = call(service, request("s3://raw/doc.txt", mime_type="application/octet-stream"))

    assert response.parse_status == parser_pb2.PARSE_STATUS_FAILED
    assert response.error.code == "UNSUPPORTED_FORMAT"
    assert response.warnings == ["unsupported MIME type: application/octet-stream"]


def test_table_mapping_preserves_section_path_and_markdown_linearization() -> None:
    reader = MemoryReader(
        StoredObject(
            b"# Results\n\n| Name | Value |\n| --- | --- |\n| A | B |\n",
            "text/markdown",
        )
    )
    service = ParserServiceServicer(reader)

    response = call(service, request("s3://raw/table.md"))

    table = response.ir.tables[0]
    if "section_path" not in table.DESCRIPTOR.fields_by_name:
        pytest.skip("repository-local generated parser stub predates TableBlock fields")
    assert table.section_path == "1"
    assert table.linearized_text == "| Name | Value |\n| --- | --- |\n| A | B |"
    assert dict(table.rows[0].cells) == {"Name": "A", "Value": "B"}


def test_partial_parse_status_and_warnings_are_propagated() -> None:
    reader = MemoryReader(StoredObject(b"content", "text/plain"))
    service = ParserServiceServicer(reader, parser=PartialParser())

    response = call(service, request("s3://raw/partial.txt"))

    assert response.parse_status == parser_pb2.PARSE_STATUS_PARTIAL
    assert response.warnings == ["one block could not be normalized"]
    assert response.error.code == "PARSE_PARTIAL"


def test_damaged_pdf_fails_with_readable_warning() -> None:
    reader = MemoryReader(StoredObject(b"%PDF-damaged", "application/pdf"))
    service = ParserServiceServicer(reader, pdf_backend=FailingPdfBackend())

    response = call(service, request("s3://raw/damaged.pdf", mime_type="application/pdf"))

    assert response.parse_status == parser_pb2.PARSE_STATUS_FAILED
    assert response.error.code == "PDF_PARSE_FAILED"
    assert response.warnings == ["damaged PDF: no readable pages"]


def test_pdf_timeout_is_configurable_and_fail_closed() -> None:
    reader = MemoryReader(StoredObject(b"%PDF-synthetic", "application/pdf"))
    service = ParserServiceServicer(
        reader,
        pdf_backend=SlowPdfBackend(),
        pdf_timeout_seconds=0.001,
    )

    response = call(service, request("s3://raw/slow.pdf"))

    assert response.parse_status == parser_pb2.PARSE_STATUS_FAILED
    assert response.error.code == "PDF_PARSE_TIMEOUT"
    assert "exceeded" in response.warnings[0]


def test_parser_settings_expose_default_and_custom_pdf_timeout() -> None:
    assert ParserSettings().pdf_timeout_seconds == 120.0
    assert ParserSettings(pdf_timeout_seconds=7.5).pdf_timeout_seconds == 7.5


def test_request_contract_does_not_accept_raw_document_bytes() -> None:
    fields = parser_pb2.ParseDocumentRequest.DESCRIPTOR.fields_by_name

    assert "storage_uri" in fields
    assert "payload" not in fields
    assert "content" not in fields
    assert "raw_bytes" not in fields


def test_s3_object_store_reads_stream_and_preserves_content_type() -> None:
    class FakeClient:
        def get_object(self, **kwargs: Any) -> dict[str, Any]:  # noqa: ANN401
            assert kwargs == {"Bucket": "raw", "Key": "doc.txt"}
            return {"Body": io.BytesIO(b"safe synthetic text"), "ContentType": "text/plain"}

    store = S3ObjectStore(ParserSettings(), client=FakeClient())

    stored = store.read("s3://raw/doc.txt")

    assert stored == StoredObject(b"safe synthetic text", "text/plain")


def test_service_rejects_missing_uri_before_storage_access() -> None:
    reader = MemoryReader(StoredObject(b"unused", "text/plain"))
    response = call(ParserServiceServicer(reader), request("   "))

    assert response.error.code == "INVALID_ARGUMENT"
    assert reader.calls == []


def test_service_maps_storage_errors_without_exposing_exception_text() -> None:
    class FailingReader:
        def read(self, storage_uri: str) -> StoredObject:
            del storage_uri
            raise ObjectStoreError("STORAGE_READ_FAILED", "synthetic storage failure")

    response = call(ParserServiceServicer(FailingReader()), request("s3://raw/doc.txt"))

    assert response.error.code == "STORAGE_READ_FAILED"
    assert response.error.message == "synthetic storage failure"


def test_service_rejects_unknown_extension_and_empty_parse() -> None:
    unknown = call(
        ParserServiceServicer(MemoryReader(StoredObject(b"data", ""))),
        request("s3://raw/doc.bin"),
    )
    empty = call(
        ParserServiceServicer(MemoryReader(StoredObject(b"", "text/plain"))),
        request("s3://raw/empty.txt"),
    )

    assert unknown.error.code == "UNSUPPORTED_FORMAT"
    assert "unsupported document format" in unknown.warnings[0]
    assert empty.error.code == "PARSE_FAILED"


def test_pdf_backend_none_fails_closed_and_unknown_backend_is_rejected() -> None:
    assert build_pdf_backend(ParserSettings(pdf_backend="none")) is None

    with pytest.raises(PdfBackendError) as error:
        build_pdf_backend(ParserSettings(pdf_backend="synthetic"))

    assert error.value.code == "PDF_BACKEND_UNSUPPORTED"
