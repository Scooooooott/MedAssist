from __future__ import annotations

import csv
from pathlib import Path
from typing import Never, cast

import grpc  # type: ignore[import-untyped]
import pytest
from google.protobuf import text_format  # type: ignore[import-untyped]
from google.protobuf.message import Message  # type: ignore[import-untyped]
from medassist.contracts.v1 import model_pb2  # type: ignore[import-not-found]

from model_svc.core import EmbeddingBackend, EmbeddingInputType, RerankBackend
from model_svc.grpc_service import ModelService

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "contracts" / "conformance" / "v1"


def _cases() -> list[dict[str, str]]:
    with (FIXTURES / "cases.tsv").open(encoding="utf-8", newline="") as stream:
        return [
            row
            for row in csv.DictReader(stream, delimiter="\t")
            if row["contract"] in {"embedding", "rerank"}
        ]


def _message(case: dict[str, str], response: bool = False) -> Message:
    if case["contract"] == "embedding":
        return model_pb2.EmbedResponse() if response else model_pb2.EmbedRequest()
    return model_pb2.RerankResponse() if response else model_pb2.RerankRequest()


def _parse(path: str, message: Message) -> Message:
    return text_format.Parse((FIXTURES / path).read_text(encoding="utf-8"), message)


@pytest.mark.parametrize("case", _cases(), ids=lambda case: f"{case['contract']}-{case['id']}")
def test_model_service_reads_shared_contract_fixtures(case: dict[str, str]) -> None:
    request = _parse(case["request"], _message(case))
    assert request == request.__class__.FromString(request.SerializeToString())
    if case["response"] == "-":
        assert case["grpc_status"] == case["error_code"]
        return
    response = _parse(case["response"], _message(case, response=True))
    assert response == response.__class__.FromString(response.SerializeToString())


class _Embedding:
    model_name = "fixture-embed"
    model_version = "v1"
    dimension = 3
    max_length = 1024

    @property
    def ready(self) -> bool:
        return True

    @property
    def not_ready_reason(self) -> str | None:
        return None

    def warmup(self) -> bool:
        return True

    def unload(self) -> None:
        return None

    def embed(self, texts: list[str], input_type: EmbeddingInputType) -> list[list[float]]:
        del input_type
        vectors = [
            [0.1, -0.25, 1.2345679],
            [3.4028235e38, 1.17549435e-38, -0.0],
            [-1.0, 0.5, 2.0],
        ]
        return vectors[: len(texts)]


class _Reranker:
    model_name = "fixture-reranker"
    model_version = "v1"
    max_length = 512
    batch_size = 3

    @property
    def ready(self) -> bool:
        return True

    @property
    def not_ready_reason(self) -> str | None:
        return None

    def warmup(self) -> bool:
        return True

    def rerank(self, query: str, candidates: list[tuple[str, str]]) -> list[float]:
        del query
        scores = {"candidate-low": 0.25, "candidate-high": 0.875, "candidate": 0.875}
        scores.update({"a": 0.25, "b": 0.5, "c": 0.875})
        return [scores[candidate_id] for candidate_id, _text in candidates]


class _AbortError(RuntimeError):
    def __init__(self, code: grpc.StatusCode, details: str) -> None:
        super().__init__(details)
        self.code = code


class _Context:
    def abort(self, code: grpc.StatusCode, details: str) -> Never:
        raise _AbortError(code, details)


@pytest.mark.parametrize(
    ("contract", "case_id"),
    [
        ("embedding", "normal-full"),
        ("embedding", "empty-input"),
        ("embedding", "batch-special"),
        ("rerank", "normal-full"),
        ("rerank", "batch-special"),
    ],
)
def test_model_service_matches_shared_success_responses(contract: str, case_id: str) -> None:
    case = next(case for case in _cases() if case["contract"] == contract and case["id"] == case_id)
    request = _parse(case["request"], _message(case))
    expected = _parse(case["response"], _message(case, response=True))
    service = ModelService(
        cast(EmbeddingBackend, _Embedding()),
        cast(RerankBackend, _Reranker()),
        max_rerank_candidates=3,
    )

    if contract == "embedding":
        actual = service.Embed(request, cast(grpc.ServicerContext, _Context()))
    else:
        actual = service.Rerank(request, cast(grpc.ServicerContext, _Context()))

    assert actual == expected


def test_rerank_empty_query_uses_shared_transport_error() -> None:
    case = next(
        case for case in _cases() if case["id"] == "empty-input" and case["contract"] == "rerank"
    )
    request = _parse(case["request"], _message(case))
    service = ModelService(cast(EmbeddingBackend, _Embedding()), cast(RerankBackend, _Reranker()))

    with pytest.raises(_AbortError) as error:
        service.Rerank(request, cast(grpc.ServicerContext, _Context()))

    assert error.value.code.name == case["grpc_status"]
    assert not model_pb2.RerankResponse().HasField("error")
