from __future__ import annotations

import logging
import signal
import threading
from collections.abc import Callable
from concurrent import futures
from types import FrameType

import grpc  # type: ignore[import-untyped, unused-ignore]
from grpc_health.v1 import (  # type: ignore[import-untyped, unused-ignore]
    health,
    health_pb2,
    health_pb2_grpc,
)

from medassist_common.config import BaseServiceSettings
from medassist_common.observability import (
    ServiceMetrics,
    configure_tracing,
    grpc_server_interceptors,
    start_metrics_server,
)

LOGGER = logging.getLogger(__name__)


def serve_health(
    settings: BaseServiceSettings,
    register_servicers: Callable[[grpc.Server], None] | None = None,
    readiness: Callable[[], bool] | None = None,
    process_model: str = "bounded_thread_pool",
    on_shutdown: Callable[[], None] | None = None,
) -> None:
    stop_tracing = configure_tracing(
        settings.service_name,
        settings.otlp_endpoint,
        insecure=settings.otlp_insecure,
        sample_ratio=settings.tracing_sample_ratio,
        enabled=settings.tracing_enabled,
    )
    metrics = ServiceMetrics(settings.service_name, process_model)
    grpc_executor = futures.ThreadPoolExecutor(max_workers=settings.grpc_workers)
    server = grpc.server(
        grpc_executor,
        maximum_concurrent_rpcs=settings.grpc_max_concurrent_rpcs,
        interceptors=grpc_server_interceptors(),
    )
    if register_servicers is not None:
        register_servicers(server)
    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    server.add_insecure_port(f"[::]:{settings.grpc_port}")
    stop_readiness = threading.Event()

    def refresh_readiness() -> bool:
        try:
            is_ready = readiness is None or readiness()
        except Exception:  # noqa: BLE001 - readiness must fail closed.
            LOGGER.exception("readiness probe failed")
            is_ready = False
        status = (
            health_pb2.HealthCheckResponse.SERVING
            if is_ready
            else health_pb2.HealthCheckResponse.NOT_SERVING
        )
        health_servicer.set("", status)
        health_servicer.set(settings.service_name, status)
        metrics.set_ready(is_ready)
        return is_ready

    initial_ready = refresh_readiness()

    def monitor_readiness() -> None:
        while not stop_readiness.wait(settings.readiness_poll_seconds):
            refresh_readiness()

    def stop(_signum: int, _frame: FrameType | None) -> None:
        LOGGER.info("stopping gRPC server")
        stop_readiness.set()
        server.stop(settings.shutdown_grace_seconds)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    stop_metrics_server = start_metrics_server(settings.metrics_port)
    server.start()
    readiness_thread = threading.Thread(
        target=monitor_readiness,
        name=f"{settings.service_name}-readiness",
        daemon=True,
    )
    readiness_thread.start()
    LOGGER.info(
        "gRPC server started",
        extra={"port": settings.grpc_port, "ready": initial_ready},
    )
    try:
        server.wait_for_termination()
    finally:
        stop_readiness.set()
        readiness_thread.join(timeout=settings.readiness_poll_seconds + 1)
        if on_shutdown is not None:
            on_shutdown()
        grpc_executor.shutdown(wait=True, cancel_futures=True)
        stop_metrics_server()
        stop_tracing()
