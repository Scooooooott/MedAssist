from __future__ import annotations

from concurrent import futures

import grpc
import pytest

from medassist.contracts.v1 import model_pb2, model_pb2_grpc
from model_svc.core import DeterministicEmbeddingModel, OnnxBgeM3Backend
from model_svc.grpc_service import ModelService


def test_embed_servicer_returns_vectors_and_metadata() -> None:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
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


def test_rerank_is_explicitly_unimplemented() -> None:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    model_pb2_grpc.add_ModelServiceServicer_to_server(
        ModelService(DeterministicEmbeddingModel(dimension=4)), server
    )
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as error:
                model_pb2_grpc.ModelServiceStub(channel).Rerank(model_pb2.RerankRequest(query="q"))
        assert error.value.code() == grpc.StatusCode.UNIMPLEMENTED
        assert "M1.4" in error.value.details()
    finally:
        server.stop(0).wait()


def test_unready_backend_is_not_serving_and_rejects_embed() -> None:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
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
        assert "model file is missing" in error.value.details()
    finally:
        server.stop(0).wait()


def test_server_module_imports_without_loading_model() -> None:
    from model_svc import server

    assert callable(server.main)
