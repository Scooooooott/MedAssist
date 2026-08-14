# ruff: noqa: E402, I001
from __future__ import annotations

import csv
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Never, cast

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "python-services" / "_generated"))

import grpc
import pytest
from google.protobuf import text_format
from google.protobuf.message import Message
from medassist.contracts.v1 import deid_pb2  # noqa: E402

from deid_svc.core import Deidentifier, DeidResult, PhiEntity  # noqa: E402
from deid_svc.grpc_service import DeidService  # noqa: E402

FIXTURES = ROOT / "contracts" / "conformance" / "v1"


def _cases() -> list[dict[str, str]]:
    with (FIXTURES / "cases.tsv").open(encoding="utf-8", newline="") as stream:
        return [row for row in csv.DictReader(stream, delimiter="\t") if row["contract"] == "deid"]


def _message(case: dict[str, str], response: bool = False) -> Message:
    if case["rpc"] == "Detect":
        return deid_pb2.DetectResponse() if response else deid_pb2.DetectRequest()
    return deid_pb2.AnonymizeResponse() if response else deid_pb2.AnonymizeRequest()


def _parse(path: str, message: Message) -> Message:
    return text_format.Parse((FIXTURES / path).read_text(encoding="utf-8"), message)


@pytest.mark.parametrize("case", _cases(), ids=lambda case: case["id"])
def test_deid_reads_shared_contract_fixtures(case: dict[str, str]) -> None:
    request = _parse(case["request"], _message(case))
    assert request == type(request).FromString(request.SerializeToString())
    if case["response"] == "-":
        assert case["grpc_status"] == case["error_code"]
        return
    response = _parse(case["response"], _message(case, response=True))
    assert response == type(response).FromString(response.SerializeToString())


class _Context:
    def abort(self, code: grpc.StatusCode, details: str) -> Never:
        raise RuntimeError(f"{code.name}: {details}")


@dataclass
class _Backend:
    ready: bool = True
    policy_version: str = "fixture-policy-v1"

    def detect(self, text: str) -> list[PhiEntity]:
        if not text:
            return []
        return [PhiEntity("EMAIL_ADDRESS", 0, len(text), 0.987654321, "fixture-recognizer")]

    def anonymize(
        self,
        text: str,
        policy: str = "SAFE_HARBOR_SURROGATE",
        document_key: str | None = None,
    ) -> DeidResult:
        assert policy == "SAFE_HARBOR_REDACT"
        assert document_key == "fixture-document"
        return DeidResult("[EMAIL_ADDRESS]", self.detect(text), self.policy_version)


@pytest.mark.parametrize("case_id", ["detect-normal", "anonymize-normal", "empty-input"])
def test_deid_service_matches_shared_success_responses(case_id: str) -> None:
    case = next(case for case in _cases() if case["id"] == case_id)
    request = _parse(case["request"], _message(case))
    expected = _parse(case["response"], _message(case, response=True))
    service = DeidService(cast(Deidentifier, _Backend()))

    if case["rpc"] == "Detect":
        actual = service.Detect(request, cast(grpc.ServicerContext, _Context()))
    else:
        actual = service.Anonymize(request, cast(grpc.ServicerContext, _Context()))

    assert actual == expected
    if case_id == "empty-input":
        assert request.SerializeToString() == b""
        assert not expected.HasField("error")
