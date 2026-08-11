from __future__ import annotations

from concurrent import futures

import grpc  # type: ignore[import-untyped]
import pytest
from medassist.contracts.v1 import model_pb2, model_pb2_grpc  # type: ignore[import-not-found]
from model_svc.core import (
    DeterministicEmbeddingModel,
    DeterministicReranker,
    OnnxBgeM3Backend,
    OnnxCrossEncoderReranker,
)
from model_svc.grpc_service import ModelService
from model_svc.registry import EmbeddingModelRegistry, RegisteredEmbeddingModel


def test_embed_servicer_returns_vectors_and_metadata() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4)), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            response = model_pb2_grpc.ModelServiceStub(channel).Embed(
                model_pb2.EmbedRequest(
                    texts=["heart failure", "hypertension"],
                    input_type=model_pb2.EMBEDDING_INPUT_TYPE_QUERY,
                )
            )
        assert response.model_name == "bge-m3-test"
        assert response.model_version == "test-deterministic"
        assert response.dimension == 4
        assert len(response.vectors) == 2
        assert all(len(vector.values) == 4 for vector in response.vectors)
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_multi_model_embed_selects_exact_identity_and_preserves_batch_order() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    medical = DeterministicEmbeddingModel(
        dimension=4, model_name="medical-domain", model_version="med-v1"
    )
    lightweight = DeterministicEmbeddingModel(
        dimension=7, model_name="lightweight", model_version="light-v1"
    )
    registry = EmbeddingModelRegistry(
        (
            RegisteredEmbeddingModel(
                name=medical.model_name,
                version=medical.model_version,
                dimension=medical.dimension,
                backend="deterministic-test",
                model_path="/models/medical.onnx",
                implementation=medical,
            ),
            RegisteredEmbeddingModel(
                name=lightweight.model_name,
                version=lightweight.model_version,
                dimension=lightweight.dimension,
                backend="deterministic-test",
                model_path="/models/lightweight.onnx",
                implementation=lightweight,
            ),
        )
    )
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(ModelService(registry), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            response = model_pb2_grpc.ModelServiceStub(channel).Embed(
                model_pb2.EmbedRequest(
                    model_name="lightweight@light-v1",
                    texts=["first", "second"],
                    input_type=model_pb2.EMBEDDING_INPUT_TYPE_PASSAGE,
                )
            )
        assert response.model_name == "lightweight"
        assert response.model_version == "light-v1"
        assert response.dimension == 7
        assert len(response.vectors) == 2
        assert all(len(vector.values) == 7 for vector in response.vectors)
        assert response.vectors[0].values != response.vectors[1].values
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


@pytest.mark.parametrize("selector", ["unknown@v1", "medical-domain@wrong"])
def test_multi_model_embed_rejects_unknown_or_wrong_version(selector: str) -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    backend = DeterministicEmbeddingModel(
        dimension=4, model_name="medical-domain", model_version="med-v1"
    )
    registry = EmbeddingModelRegistry(
        (
            RegisteredEmbeddingModel(
                name=backend.model_name,
                version=backend.model_version,
                dimension=backend.dimension,
                backend="deterministic-test",
                model_path="/models/medical.onnx",
                implementation=backend,
            ),
        )
    )
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(ModelService(registry), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Embed(
                    model_pb2.EmbedRequest(
                        model_name=selector,
                        texts=["must not run"],
                        input_type=model_pb2.EMBEDDING_INPUT_TYPE_QUERY,
                    )
                )
        assert error.value.code() == grpc.StatusCode.INVALID_ARGUMENT
        assert "not registered" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


class WrongDimensionEmbedding(DeterministicEmbeddingModel):
    def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
        return [[0.0, 0.0, 0.0] for _ in texts]


def test_embed_rejects_backend_dimension_mismatch() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    backend = WrongDimensionEmbedding(dimension=4, model_name="bad-output", model_version="v1")
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(ModelService(backend), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Embed(
                    model_pb2.EmbedRequest(
                        texts=["bad output"],
                        input_type=model_pb2.EMBEDDING_INPUT_TYPE_QUERY,
                    )
                )
        assert error.value.code() == grpc.StatusCode.INTERNAL
        assert "invalid vector dimensions" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_rerank_orders_candidates_and_returns_model_identity() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(
            DeterministicEmbeddingModel(dimension=4),
            DeterministicReranker(model_name="test-reranker", model_version="v1"),
        ),
        server,
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            response = model_pb2_grpc.ModelServiceStub(channel).Rerank(
                model_pb2.RerankRequest(
                    query="heart failure",
                    candidates=[
                        model_pb2.RerankCandidate(id="low", text="hypertension"),
                        model_pb2.RerankCandidate(id="high", text="heart failure treatment"),
                    ],
                )
            )
        assert [result.id for result in response.results] == ["high", "low"]
        assert [result.rank for result in response.results] == [1, 2]
        assert response.model_name == "test-reranker"
        assert response.model_version == "v1"
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_rerank_is_explicitly_unimplemented_when_disabled() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4)), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Rerank(
                    model_pb2.RerankRequest(
                        query="q",
                        candidates=[model_pb2.RerankCandidate(id="1", text="text")],
                    )
                )
        assert error.value.code() == grpc.StatusCode.UNIMPLEMENTED
        assert "not enabled" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_rerank_rejects_invalid_model_name() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4), DeterministicReranker()), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Rerank(
                    model_pb2.RerankRequest(
                        query="q",
                        model_name="wrong-model",
                        candidates=[model_pb2.RerankCandidate(id="1", text="text")],
                    )
                )
        assert error.value.code() == grpc.StatusCode.INVALID_ARGUMENT
        assert "does not match" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_rerank_rejects_candidates_over_configured_cap() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4), DeterministicReranker(), 1), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Rerank(
                    model_pb2.RerankRequest(
                        query="q",
                        candidates=[
                            model_pb2.RerankCandidate(id="1", text="one"),
                            model_pb2.RerankCandidate(id="2", text="two"),
                        ],
                    )
                )
        assert error.value.code() == grpc.StatusCode.INVALID_ARGUMENT
        assert "configured limit 1" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_rerank_rejects_not_ready_production_backend() -> None:
    reranker = OnnxCrossEncoderReranker(
        model_path=None,
        tokenizer_path=None,
        model_name="online",
        model_version="v1",
    )
    assert reranker.warmup() is False
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4), reranker), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Rerank(
                    model_pb2.RerankRequest(
                        query="q",
                        candidates=[model_pb2.RerankCandidate(id="1", text="text")],
                    )
                )
        assert error.value.code() == grpc.StatusCode.FAILED_PRECONDITION
        assert "model file is missing" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


class FailingReranker(DeterministicReranker):
    def rerank(self, query: str, candidates: list[tuple[str, str]]) -> list[float]:
        raise RuntimeError("synthetic inference failure")


def test_rerank_maps_inference_errors_to_internal() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4), FailingReranker()), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Rerank(
                    model_pb2.RerankRequest(
                        query="q",
                        candidates=[model_pb2.RerankCandidate(id="1", text="text")],
                    )
                )
        assert error.value.code() == grpc.StatusCode.INTERNAL
        assert "rerank inference failed" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_unready_backend_is_not_serving_and_rejects_embed() -> None:
    executor = futures.ThreadPoolExecutor(max_workers=2)
    server = grpc.server(executor)
    backend = OnnxBgeM3Backend(model_path=None, tokenizer_path=None)
    assert backend.warmup() is False
    model_pb2_grpc.add_ModelServiceServicer_to_server(ModelService(backend), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Embed(
                    model_pb2.EmbedRequest(
                        texts=["must not be embedded"],
                        input_type=model_pb2.EMBEDDING_INPUT_TYPE_PASSAGE,
                    )
                )
        assert error.value.code() == grpc.StatusCode.FAILED_PRECONDITION
        assert "NOT_SERVING" in error.value.details()
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)


def test_server_module_imports_without_loading_model() -> None:
    from model_svc import server

    assert callable(server.main)
