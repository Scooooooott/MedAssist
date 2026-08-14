from typing import Any

import numpy as np
import pytest

from model_svc.backend import build_backend, build_reranker
from model_svc.core import (
    BackendNotReadyError,
    DeterministicEmbeddingModel,
    DeterministicReranker,
    OnnxBgeM3Backend,
    OnnxCrossEncoderReranker,
)
from model_svc.settings import ModelSettings


def test_embedding_is_deterministic_and_dimensioned() -> None:
    model = DeterministicEmbeddingModel(dimension=16)

    first = model.embed(["heart failure"], "query")[0]
    second = model.embed(["heart failure"], "query")[0]

    assert first == second
    assert len(first) == 16


def test_deterministic_backend_is_explicitly_ready_for_injection() -> None:
    model = DeterministicEmbeddingModel(dimension=8)

    assert model.ready
    assert model.warmup()
    assert len(model.embed(["passage"], "passage")[0]) == 8


def test_onnx_backend_fails_closed_without_a_model_bundle() -> None:
    backend = OnnxBgeM3Backend(model_path=None, tokenizer_path=None)

    assert backend.warmup() is False
    assert backend.ready is False
    assert "model file is missing" in (backend.not_ready_reason or "")

    try:
        backend.embed(["query"], "query")
    except BackendNotReadyError:
        pass
    else:
        raise AssertionError("an unready production backend must not return vectors")


def test_onnx_backend_unload_releases_runtime_objects() -> None:
    backend = OnnxBgeM3Backend(model_path=None, tokenizer_path=None)
    backend._session = object()
    backend._tokenizer = object()
    backend._not_ready_reason = None

    backend.unload()

    assert backend._session is None
    assert backend._tokenizer is None
    assert not backend.ready
    assert backend.not_ready_reason == "model is not resident"


def test_backend_validates_limits_and_input_type() -> None:
    with pytest.raises(ValueError):
        DeterministicEmbeddingModel(dimension=0)
    with pytest.raises(ValueError):
        DeterministicEmbeddingModel(max_length=1025)
    with pytest.raises(ValueError):
        OnnxBgeM3Backend(None, None, batch_size=0)
    with pytest.raises(ValueError):
        OnnxBgeM3Backend(None, None, quantization="fp16")

    model = DeterministicEmbeddingModel(dimension=4)
    with pytest.raises(ValueError):
        model.embed(["text"], "other")  # type: ignore[arg-type]
    assert model.embed([], "query") == []


class FakeEncoding:
    ids = [1, 2]
    attention_mask = [1, 1]
    type_ids = [0, 0]


class FakeTokenizer:
    def encode_batch(self, texts: list[str]) -> list[FakeEncoding]:
        return [FakeEncoding() for _ in texts]


class FakeInput:
    def __init__(self, name: str) -> None:
        self.name = name


class FakeSession:
    def get_inputs(self) -> list[FakeInput]:
        return [FakeInput("input_ids"), FakeInput("attention_mask"), FakeInput("token_type_ids")]

    def run(
        self,
        _outputs: Any,  # noqa: ANN401
        feeds: dict[str, Any],
    ) -> list[np.ndarray[Any, Any]]:
        assert set(feeds) == {"input_ids", "attention_mask", "token_type_ids"}
        return [np.ones((len(feeds["input_ids"]), 2, 3), dtype=np.float32)]


def test_onnx_backend_batches_and_normalizes_hidden_states() -> None:
    backend = OnnxBgeM3Backend(None, None, dimension=3, batch_size=1)
    backend._tokenizer = FakeTokenizer()
    backend._session = FakeSession()
    backend._not_ready_reason = None

    vectors = backend.embed(["one", "two"], "passage")

    assert len(vectors) == 2
    assert all(len(vector) == 3 for vector in vectors)
    assert all(abs(sum(value * value for value in vector) - 1.0) < 1e-5 for vector in vectors)


def test_backend_factory_requires_explicit_test_switch() -> None:
    settings = ModelSettings(
        backend="deterministic-test",
        allow_deterministic_test_backend=True,
        dimension=4,
    )
    assert build_backend(settings).ready

    with pytest.raises(RuntimeError):
        build_backend(settings.model_copy(update={"allow_deterministic_test_backend": False}))


def test_onnx_runtime_thread_settings_are_explicit() -> None:
    settings = ModelSettings(runtime_intra_op_threads=2, runtime_inter_op_threads=1)
    backend = build_backend(settings)
    reranker = build_reranker(settings)

    assert isinstance(backend, OnnxBgeM3Backend)
    assert backend.intra_op_threads == 2
    assert backend.inter_op_threads == 1
    assert isinstance(reranker, OnnxCrossEncoderReranker)
    assert reranker.intra_op_threads == 2
    assert reranker.inter_op_threads == 1


def test_reranker_is_deterministic_only_when_directly_injected() -> None:
    reranker = DeterministicReranker(model_name="test-reranker", model_version="v1")

    scores = reranker.rerank(
        "heart failure",
        [("low", "hypertension"), ("high", "heart failure treatment")],
    )

    assert scores == [0.0, 2.0]
    assert reranker.ready


def test_reranker_factory_selects_online_and_offline_profiles() -> None:
    online = build_reranker(ModelSettings(rerank_profile="online"))
    offline = build_reranker(ModelSettings(rerank_profile="offline"))

    assert online.model_name == "cross-encoder/ms-marco-MiniLM-L-6-v2"
    assert offline.model_name == "BAAI/bge-reranker-v2-m3"
    assert not online.ready
    assert not offline.ready


class FakePairEncoding:
    ids = [1, 2, 3]
    attention_mask = [1, 1, 1]
    type_ids = [0, 0, 1]


class FakePairTokenizer:
    def encode(self, query: str, text: str) -> FakePairEncoding:
        assert query == "question"
        assert text in {"first", "second"}
        return FakePairEncoding()


class FakeRerankSession:
    def get_inputs(self) -> list[FakeInput]:
        return [FakeInput("input_ids"), FakeInput("attention_mask"), FakeInput("token_type_ids")]

    def run(
        self,
        _outputs: Any,  # noqa: ANN401
        feeds: dict[str, Any],
    ) -> list[np.ndarray[Any, Any]]:
        assert set(feeds) == {"input_ids", "attention_mask", "token_type_ids"}
        assert feeds["input_ids"].shape == (2, 3)
        return [np.asarray([[0.1, 0.2], [0.9, 0.3]], dtype=np.float32)]


def test_onnx_reranker_batches_pair_inputs_and_reads_positive_logit() -> None:
    reranker = OnnxCrossEncoderReranker(None, None, "test-onnx", "v1", batch_size=2)
    reranker._tokenizer = FakePairTokenizer()
    reranker._session = FakeRerankSession()
    reranker._not_ready_reason = None

    scores = reranker.rerank("question", [("first", "first"), ("second", "second")])

    assert scores == pytest.approx([0.2, 0.3])
