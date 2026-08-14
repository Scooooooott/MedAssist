from __future__ import annotations

import threading
from concurrent import futures

import grpc  # type: ignore[import-untyped]
import pytest
from medassist.contracts.v1 import model_pb2, model_pb2_grpc  # type: ignore[import-not-found]
from medassist_common import BoundedExecutor, WorkRejectedError, safe_span_attributes
from medassist_common.observability import grpc_server_interceptors
from opentelemetry import context, trace
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags
from prometheus_client import generate_latest

from model_svc.core import DeterministicEmbeddingModel
from model_svc.execution import ModelExecutionPools
from model_svc.grpc_service import ModelService
from model_svc.settings import ModelSettings


def test_query_path_has_no_wait_queue_and_does_not_block_passage_capacity() -> None:
    settings = ModelSettings(
        query_worker_threads=1,
        worker_threads=1,
        work_queue_capacity=2,
    )
    pools = ModelExecutionPools(settings)
    started = threading.Event()
    release = threading.Event()

    def occupy_query() -> str:
        started.set()
        release.wait(timeout=2)
        return "done"

    worker = threading.Thread(
        target=lambda: pools.execute_embed("query", occupy_query, batch_size=1)
    )
    worker.start()
    assert started.wait(timeout=1)
    try:
        assert pools.query.queue_capacity == 0
        with pytest.raises(WorkRejectedError):
            pools.execute_embed("query", lambda: "queued", batch_size=1)
        assert pools.execute_embed("passage", lambda: "batched", batch_size=8) == "batched"
    finally:
        release.set()
        worker.join(timeout=2)
        pools.shutdown()
    assert not worker.is_alive()


def test_bounded_executor_propagates_active_trace_context() -> None:
    executor = BoundedExecutor(
        service_name="model-svc",
        process_model="single_process_thread_pool",
        queue_name="trace-test",
        max_workers=1,
        queue_capacity=0,
    )
    expected_trace_id = int("1234567890abcdef1234567890abcdef", 16)
    span_context = SpanContext(
        trace_id=expected_trace_id,
        span_id=int("1234567890abcdef", 16),
        is_remote=True,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )
    token = context.attach(trace.set_span_in_context(NonRecordingSpan(span_context)))
    try:
        observed = executor.execute(
            "Embed",
            "query",
            lambda: trace.get_current_span().get_span_context().trace_id,
        )
    finally:
        context.detach(token)
        executor.shutdown()
    assert observed == expected_trace_id


def test_grpc_interceptor_extracts_w3c_traceparent_without_collector() -> None:
    expected_trace_id = int("1234567890abcdef1234567890abcdef", 16)

    class TraceRecordingBackend(DeterministicEmbeddingModel):
        observed_trace_id = 0

        def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
            self.observed_trace_id = trace.get_current_span().get_span_context().trace_id
            return super().embed(texts, input_type)  # type: ignore[arg-type]

    backend = TraceRecordingBackend(dimension=4)
    executor = futures.ThreadPoolExecutor(max_workers=1)
    server = grpc.server(executor, interceptors=grpc_server_interceptors())
    model_pb2_grpc.add_ModelServiceServicer_to_server(ModelService(backend), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            model_pb2_grpc.ModelServiceStub(channel).Embed(
                model_pb2.EmbedRequest(
                    texts=["synthetic query"],
                    input_type=model_pb2.EMBEDDING_INPUT_TYPE_QUERY,
                ),
                metadata=(
                    (
                        "traceparent",
                        "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01",
                    ),
                ),
            )
    finally:
        server.stop(0).wait()
        executor.shutdown(wait=True, cancel_futures=True)
    assert backend.observed_trace_id == expected_trace_id


def test_span_attribute_allowlist_removes_user_and_document_text() -> None:
    attributes = safe_span_attributes(
        {
            "service.name": "model-svc",
            "rpc.method": "Embed",
            "batch.size": 2,
            "query.text": "synthetic patient text",
            "document.text": "synthetic source text",
        }
    )

    assert attributes == {
        "service.name": "model-svc",
        "rpc.method": "Embed",
        "batch.size": 2,
    }


def test_prometheus_metrics_use_bounded_process_labels_without_payload() -> None:
    pools = ModelExecutionPools(ModelSettings())
    try:
        pools.execute_embed("passage", lambda: "ok", batch_size=4)
        payload = generate_latest().decode("utf-8")
    finally:
        pools.shutdown()

    assert "medassist_python_rpc_requests_total" in payload
    assert 'process_model="single_process_thread_pool"' in payload
    assert 'queue="passage"' in payload
    assert "synthetic patient text" not in payload
    assert "synthetic source text" not in payload


def test_readiness_includes_model_and_execution_pool_state() -> None:
    pools = ModelExecutionPools(ModelSettings())
    service = ModelService(DeterministicEmbeddingModel(dimension=4), execution=pools)

    assert service.registry.warmup()
    assert service.readiness()
    pools.shutdown()
    assert not service.readiness()
