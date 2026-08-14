# ruff: noqa: E402, I001
from __future__ import annotations

import csv
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import cast

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "python-services" / "_generated"))

import grpc
import pytest
from google.protobuf import text_format
from google.protobuf.message import Message
from medassist.contracts.v1 import parser_pb2  # noqa: E402
from parser_svc.core import LightweightParser, ParsedDocument, ParsedSection, ParsedTable  # noqa: E402
from parser_svc.object_store import StoredObject  # noqa: E402
from parser_svc.service import ParserServiceServicer  # noqa: E402

FIXTURES = ROOT / "contracts" / "conformance" / "v1"


def _cases() -> list[dict[str, str]]:
    with (FIXTURES / "cases.tsv").open(encoding="utf-8", newline="") as stream:
        return [
            row for row in csv.DictReader(stream, delimiter="\t") if row["contract"] == "parser"
        ]


def _parse(path: str, message: Message) -> Message:
    return text_format.Parse((FIXTURES / path).read_text(encoding="utf-8"), message)


@pytest.mark.parametrize("case", _cases(), ids=lambda case: case["id"])
def test_parser_reads_shared_contract_fixtures(case: dict[str, str]) -> None:
    request = _parse(case["request"], parser_pb2.ParseDocumentRequest())
    assert request == parser_pb2.ParseDocumentRequest.FromString(request.SerializeToString())

    if case["response"] == "-":
        assert case["grpc_status"] != "OK"
        return
    response = _parse(case["response"], parser_pb2.ParseDocumentResponse())
    assert response == parser_pb2.ParseDocumentResponse.FromString(response.SerializeToString())
    if case["error_code"]:
        assert response.error.code == case["error_code"]


@dataclass
class _Reader:
    stored: StoredObject

    def read(self, storage_uri: str) -> StoredObject:
        assert storage_uri == "s3://medical-fixtures/report.md"
        return self.stored


class _FixtureParser(LightweightParser):
    supported_suffixes = {".md"}

    def parse_bytes(self, payload: bytes, suffix: str, source: str = "") -> ParsedDocument:
        assert payload == b"fixture"
        assert suffix == ".md"
        assert source == "s3://medical-fixtures/report.md"
        child = ParsedSection("1.1", "Detail", 2, "Synthetic detail", 4, 20)
        section = ParsedSection("1", "Evidence", 1, "Synthetic evidence summary", 0, 30, [child])
        table = ParsedTable(
            "Synthetic table",
            ["Metric", "Value"],
            [{"Metric": "count", "Value": "2"}],
            31,
            60,
            "1",
            "| Metric | Value |\n| --- | --- |\n| count | 2 |",
        )
        return ParsedDocument([section], [table], {"fixture": "normal"}, [], "SUCCEEDED")


def test_parser_service_matches_shared_normal_response() -> None:
    case = next(case for case in _cases() if case["id"] == "normal-full")
    request = cast(
        parser_pb2.ParseDocumentRequest, _parse(case["request"], parser_pb2.ParseDocumentRequest())
    )
    expected = cast(
        parser_pb2.ParseDocumentResponse,
        _parse(case["response"], parser_pb2.ParseDocumentResponse()),
    )
    service = ParserServiceServicer(
        _Reader(StoredObject(b"fixture", "text/markdown")),
        parser=_FixtureParser(),
    )

    actual = service.ParseDocument(request, cast(grpc.ServicerContext, object()))

    assert actual == expected


def test_parser_service_matches_shared_empty_input_error() -> None:
    case = next(case for case in _cases() if case["id"] == "empty-input")
    request = cast(
        parser_pb2.ParseDocumentRequest, _parse(case["request"], parser_pb2.ParseDocumentRequest())
    )
    expected = cast(
        parser_pb2.ParseDocumentResponse,
        _parse(case["response"], parser_pb2.ParseDocumentResponse()),
    )
    service = ParserServiceServicer(_Reader(StoredObject(b"unused", "text/plain")))

    actual = service.ParseDocument(request, cast(grpc.ServicerContext, object()))

    assert actual == expected
    assert not request.HasField("metadata")
    assert request.SerializeToString() == b""
