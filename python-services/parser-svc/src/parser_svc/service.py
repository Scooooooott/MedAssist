from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, TimeoutError
from pathlib import PurePosixPath
from typing import Protocol
from urllib.parse import urlparse

import grpc
from medassist_common import BoundedExecutor, WorkRejectedError, configure_generated_proto_path

configure_generated_proto_path()

from medassist.contracts.v1 import common_pb2, parser_pb2, parser_pb2_grpc  # noqa: E402

from parser_svc.core import (  # noqa: E402
    LightweightParser,
    ParsedDocument,
    ParsedSection,
    ParsedTable,
)
from parser_svc.object_store import ObjectStoreError, S3ObjectStore, StoredObject  # noqa: E402
from parser_svc.pdf import PdfBackend, PdfBackendError  # noqa: E402


class ObjectReader(Protocol):
    def read(self, storage_uri: str) -> StoredObject:
        """Read an object addressed by an S3-compatible URI."""


class ParserServiceServicer(parser_pb2_grpc.ParserServiceServicer):  # type: ignore[misc]
    """Translate parser core results into the stable ParseDocument contract."""

    _mime_suffixes = {
        "text/plain": ".txt",
        "text/markdown": ".md",
        "text/html": ".html",
        "application/pdf": ".pdf",
    }

    def __init__(
        self,
        object_reader: ObjectReader,
        *,
        parser: LightweightParser | None = None,
        pdf_backend: PdfBackend | None = None,
        pdf_timeout_seconds: float = 120.0,
        executor: BoundedExecutor | None = None,
    ) -> None:
        self._object_reader = object_reader
        self._parser = parser or LightweightParser()
        self._pdf_backend = pdf_backend
        self._pdf_timeout_seconds = pdf_timeout_seconds
        self._executor = executor

    def readiness(self) -> bool:
        """Report local readiness without making a network request or logging PHI."""

        executor_ready = self._executor is None or self._executor.ready
        return self._object_reader is not None and self._parser is not None and executor_ready

    def ParseDocument(  # noqa: N802
        self,
        request: parser_pb2.ParseDocumentRequest,
        context: grpc.ServicerContext,
    ) -> parser_pb2.ParseDocumentResponse:
        del context
        if self._executor is None:
            return self._parse_document(request)
        try:
            return self._executor.execute(
                "ParseDocument",
                "offline",
                lambda: self._parse_document(request),
            )
        except WorkRejectedError:
            return self._failure(
                "RESOURCE_EXHAUSTED",
                "parser worker capacity exhausted; offline work rejected",
                attributes={"retryable": "true"},
            )

    def _parse_document(
        self,
        request: parser_pb2.ParseDocumentRequest,
    ) -> parser_pb2.ParseDocumentResponse:
        storage_uri = request.storage_uri.strip()
        if not storage_uri:
            return self._failure("INVALID_ARGUMENT", "storage_uri is required")

        try:
            stored = self._object_reader.read(storage_uri)
        except ObjectStoreError as exc:
            attributes = {exc.attribute[0]: exc.attribute[1]} if exc.attribute else {}
            return self._failure(exc.code, str(exc), attributes=attributes)
        except Exception:
            return self._failure("STORAGE_READ_FAILED", "unable to read the storage object")

        suffix = self._detect_suffix(storage_uri, request.mime_type, stored.content_type)
        request_mime = request.mime_type.split(";", 1)[0].strip().lower()
        if request_mime and request_mime not in self._mime_suffixes:
            return self._failure(
                "UNSUPPORTED_FORMAT",
                "the requested MIME type is unsupported",
                warnings=[f"unsupported MIME type: {request_mime}"],
                attributes={"mime_type": request_mime},
            )
        if suffix == ".pdf":
            if self._pdf_backend is None:
                return self._failure(
                    "PDF_BACKEND_UNAVAILABLE",
                    "no PDF backend is configured",
                    attributes={"format": "pdf"},
                )
            try:
                parsed = self._parse_pdf(stored.payload, storage_uri)
            except PdfBackendError as exc:
                return self._failure(
                    exc.code,
                    str(exc),
                    warnings=[str(exc)],
                    attributes={"format": "pdf"},
                )
            except TimeoutError:
                warning = f"PDF parsing exceeded {self._pdf_timeout_seconds:g} seconds"
                return self._failure(
                    "PDF_PARSE_TIMEOUT",
                    warning,
                    warnings=[warning],
                    attributes={"format": "pdf"},
                )
            except Exception:
                warning = "the PDF backend failed while parsing the document"
                return self._failure(
                    "PDF_PARSE_FAILED",
                    warning,
                    warnings=[warning],
                    attributes={"format": "pdf"},
                )
        elif suffix in self._parser.supported_suffixes:
            parsed = self._parser.parse_bytes(stored.payload, suffix, storage_uri)
        else:
            return self._failure(
                "UNSUPPORTED_FORMAT",
                "the object format is unsupported",
                warnings=[f"unsupported document format: {suffix or 'unknown'}"],
                attributes={"format": suffix or "unknown"},
            )

        if not parsed.sections and not parsed.tables:
            return self._failure(
                "PARSE_FAILED",
                "the parser produced no document content",
                warnings=parsed.warnings,
                attributes={"format": suffix},
            )
        return self._to_response(parsed, request, stored, suffix)

    @classmethod
    def _detect_suffix(cls, storage_uri: str, request_mime: str, object_mime: str) -> str:
        suffix = PurePosixPath(urlparse(storage_uri).path).suffix.lower()
        if suffix:
            return suffix
        for mime_type in (request_mime, object_mime):
            normalized = mime_type.split(";", 1)[0].strip().lower()
            if normalized in cls._mime_suffixes:
                return cls._mime_suffixes[normalized]
        return ""

    def _parse_pdf(self, payload: bytes, source: str) -> ParsedDocument:
        if self._pdf_backend is None:
            raise PdfBackendError("PDF_BACKEND_UNAVAILABLE", "no PDF backend is configured")
        executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="parser-pdf")
        future = executor.submit(self._pdf_backend.parse, payload, source)
        try:
            parsed = future.result(timeout=self._pdf_timeout_seconds)
        except TimeoutError:
            future.cancel()
            executor.shutdown(wait=False, cancel_futures=True)
            raise
        except BaseException:
            executor.shutdown(wait=True, cancel_futures=True)
            raise
        else:
            executor.shutdown(wait=True, cancel_futures=True)
            return parsed

    @classmethod
    def _to_response(
        cls,
        parsed: ParsedDocument,
        request: parser_pb2.ParseDocumentRequest,
        stored: StoredObject,
        suffix: str,
    ) -> parser_pb2.ParseDocumentResponse:
        metadata = dict(parsed.metadata)
        if request.source_id:
            metadata["source_id"] = request.source_id
        if request.mime_type or stored.content_type:
            metadata["mime_type"] = request.mime_type or stored.content_type
        metadata.setdefault("format", suffix.lstrip("."))
        response = parser_pb2.ParseDocumentResponse()
        response.ir.CopyFrom(
            parser_pb2.DocumentIR(
                sections=[cls._section_message(section) for section in parsed.sections],
                tables=[cls._table_message(table) for table in parsed.tables],
                metadata=metadata,
            )
        )
        response.warnings.extend(parsed.warnings)
        if parsed.status == "PARTIAL" or parsed.warnings:
            response.parse_status = parser_pb2.PARSE_STATUS_PARTIAL
            response.error.CopyFrom(
                common_pb2.ErrorDetail(
                    code="PARSE_PARTIAL",
                    message="the document was parsed with warnings",
                    attributes={"format": suffix},
                )
            )
        else:
            response.parse_status = parser_pb2.PARSE_STATUS_SUCCEEDED
        return response

    @staticmethod
    def _section_message(section: ParsedSection) -> parser_pb2.Section:
        message = parser_pb2.Section(
            path=section.path,
            heading=section.heading,
            level=section.level,
            text=section.text,
            source_range=common_pb2.SourceRange(start=section.start, end=section.end),
        )
        message.children.extend(
            ParserServiceServicer._section_message(child) for child in section.children
        )
        return message

    @staticmethod
    def _table_message(table: ParsedTable) -> parser_pb2.TableBlock:
        message = parser_pb2.TableBlock(
            caption=table.caption,
            headers=table.headers,
            source_range=common_pb2.SourceRange(start=table.start, end=table.end),
        )
        message.rows.extend(parser_pb2.TableRow(cells=row) for row in table.rows)
        fields = message.DESCRIPTOR.fields_by_name
        if "section_path" in fields:
            message.section_path = table.section_path
        if "linearized_text" in fields:
            message.linearized_text = table.linearized_text
        return message

    @staticmethod
    def _failure(
        code: str,
        message: str,
        *,
        warnings: list[str] | None = None,
        attributes: dict[str, str] | None = None,
    ) -> parser_pb2.ParseDocumentResponse:
        response = parser_pb2.ParseDocumentResponse(parse_status=parser_pb2.PARSE_STATUS_FAILED)
        if warnings:
            response.warnings.extend(warnings)
        response.error.CopyFrom(
            common_pb2.ErrorDetail(code=code, message=message, attributes=attributes or {})
        )
        return response


def build_parser_service(
    settings: object,
    *,
    object_reader: ObjectReader | None = None,
    pdf_backend: PdfBackend | None = None,
    executor: BoundedExecutor | None = None,
) -> ParserServiceServicer:
    reader = object_reader or S3ObjectStore(settings)  # type: ignore[arg-type]
    timeout_seconds = float(getattr(settings, "pdf_timeout_seconds", 120.0))
    return ParserServiceServicer(
        reader,
        pdf_backend=pdf_backend,
        pdf_timeout_seconds=timeout_seconds,
        executor=executor,
    )
