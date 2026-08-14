from __future__ import annotations

import threading
import time
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager

import grpc  # type: ignore[import-untyped, unused-ignore]
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.grpc import server_interceptor
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from prometheus_client import Counter, Gauge, Histogram, start_http_server

type SpanValue = str | bool | int | float

_TRACING_LOCK = threading.Lock()
_TRACING_PROVIDER: TracerProvider | None = None

SAFE_SPAN_ATTRIBUTES = frozenset(
    {
        "batch.size",
        "error.type",
        "model.name",
        "outcome",
        "process.model",
        "queue.capacity",
        "queue.name",
        "rpc.method",
        "service.name",
        "worker.count",
        "workload",
    }
)

_REQUESTS = Counter(
    "medassist_python_rpc_requests_total",
    "Python gRPC requests by bounded, low-cardinality outcome.",
    ("service", "operation", "outcome", "process_model"),
)
_REQUEST_LATENCY = Histogram(
    "medassist_python_rpc_duration_seconds",
    "Python gRPC request latency.",
    ("service", "operation", "process_model"),
)
_QUEUE_DEPTH = Gauge(
    "medassist_python_queue_depth",
    "Work items waiting for a bounded worker.",
    ("service", "queue", "process_model"),
)
_REJECTIONS = Counter(
    "medassist_python_work_rejected_total",
    "Work rejected before entering a full bounded queue.",
    ("service", "queue", "process_model"),
)
_QUEUE_WAIT = Histogram(
    "medassist_python_queue_wait_seconds",
    "Time between accepted work and worker execution.",
    ("service", "queue", "process_model"),
)
_EXECUTION = Histogram(
    "medassist_python_execution_seconds",
    "Time spent executing bounded service work.",
    ("service", "operation", "process_model"),
)
_READINESS = Gauge(
    "medassist_python_ready",
    "Whether the service and its required model runtime are ready.",
    ("service", "process_model"),
)


def safe_span_attributes(attributes: Mapping[str, SpanValue]) -> dict[str, SpanValue]:
    """Return only explicitly approved, text-free, low-cardinality attributes."""

    return {key: value for key, value in attributes.items() if key in SAFE_SPAN_ATTRIBUTES}


class SafeTracer:
    """Small no-export-required facade over the OpenTelemetry API."""

    def __init__(self, service_name: str) -> None:
        self._tracer = trace.get_tracer(service_name)

    @contextmanager
    def span(self, name: str, attributes: Mapping[str, SpanValue]) -> Iterator[None]:
        with self._tracer.start_as_current_span(
            name,
            attributes=safe_span_attributes(attributes),
        ):
            yield

    def completed_span(
        self,
        name: str,
        attributes: Mapping[str, SpanValue],
        *,
        elapsed: float,
    ) -> None:
        ended = time.time_ns()
        started = ended - int(elapsed * 1_000_000_000)
        span = self._tracer.start_span(
            name,
            attributes=safe_span_attributes(attributes),
            start_time=started,
        )
        span.end(end_time=ended)


class ServiceMetrics:
    def __init__(self, service_name: str, process_model: str) -> None:
        self._service_name = service_name
        self._process_model = process_model

    def observe_request(self, operation: str, outcome: str, elapsed: float) -> None:
        labels = (self._service_name, operation, outcome, self._process_model)
        _REQUESTS.labels(*labels).inc()
        _REQUEST_LATENCY.labels(
            self._service_name,
            operation,
            self._process_model,
        ).observe(elapsed)

    def set_queue_depth(self, queue_name: str, depth: int) -> None:
        _QUEUE_DEPTH.labels(
            self._service_name,
            queue_name,
            self._process_model,
        ).set(depth)

    def reject(self, queue_name: str) -> None:
        _REJECTIONS.labels(
            self._service_name,
            queue_name,
            self._process_model,
        ).inc()

    def observe_queue_wait(self, queue_name: str, elapsed: float) -> None:
        _QUEUE_WAIT.labels(
            self._service_name,
            queue_name,
            self._process_model,
        ).observe(elapsed)

    def observe_execution(self, operation: str, elapsed: float) -> None:
        _EXECUTION.labels(
            self._service_name,
            operation,
            self._process_model,
        ).observe(elapsed)

    def set_ready(self, ready: bool) -> None:
        _READINESS.labels(self._service_name, self._process_model).set(1 if ready else 0)


def grpc_server_interceptors() -> tuple[grpc.ServerInterceptor, ...]:
    """Install W3C-compatible gRPC extraction without requiring an exporter."""

    return (server_interceptor(),)  # type: ignore[no-untyped-call, unused-ignore]


def configure_tracing(
    service_name: str,
    endpoint: str,
    *,
    insecure: bool,
    sample_ratio: float,
    enabled: bool,
) -> Callable[[], None]:
    """Configure one process-wide OTLP trace pipeline and return its shutdown hook."""

    global _TRACING_PROVIDER  # noqa: PLW0603 - OpenTelemetry provider is process-global.
    if not enabled:
        return lambda: None
    if not 0.0 <= sample_ratio <= 1.0:
        raise ValueError("sample_ratio must be in [0, 1]")
    with _TRACING_LOCK:
        if _TRACING_PROVIDER is not None:
            return lambda: None
        existing_provider = trace.get_tracer_provider()
        if isinstance(existing_provider, TracerProvider):
            # Respect an SDK installed by auto-instrumentation or the embedding process.
            _TRACING_PROVIDER = existing_provider
            return lambda: None
        provider = TracerProvider(
            resource=Resource.create({SERVICE_NAME: service_name}),
            sampler=ParentBased(TraceIdRatioBased(sample_ratio)),
        )
        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint, insecure=insecure))
        )
        trace.set_tracer_provider(provider)
        _TRACING_PROVIDER = provider

    def shutdown() -> None:
        provider.force_flush(timeout_millis=5_000)
        provider.shutdown()

    return shutdown


def start_metrics_server(port: int) -> Callable[[], None]:
    if port <= 0:
        return lambda: None
    server, thread = start_http_server(port)

    def stop() -> None:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    return stop
