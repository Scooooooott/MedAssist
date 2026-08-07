from __future__ import annotations

import logging
import signal
from concurrent import futures
from collections.abc import Callable
from types import FrameType

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

from medassist_common.config import BaseServiceSettings

LOGGER = logging.getLogger(__name__)


def serve_health(
    settings: BaseServiceSettings,
    register_servicers: Callable[[grpc.Server], None] | None = None,
    readiness: Callable[[], bool] | None = None,
) -> None:
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=settings.grpc_workers),
        maximum_concurrent_rpcs=settings.grpc_max_concurrent_rpcs,
    )
    if register_servicers is not None:
        register_servicers(server)
    health_servicer = health.HealthServicer()
    is_ready = readiness is None or readiness()
    health_servicer.set(
        "",
        health_pb2.HealthCheckResponse.SERVING
        if is_ready
        else health_pb2.HealthCheckResponse.NOT_SERVING,
    )
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    server.add_insecure_port(f"[::]:{settings.grpc_port}")

    def stop(_signum: int, _frame: FrameType | None) -> None:
        LOGGER.info("stopping gRPC server")
        server.stop(settings.shutdown_grace_seconds)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    server.start()
    LOGGER.info(
        "gRPC server started",
        extra={"port": settings.grpc_port, "ready": is_ready},
    )
    server.wait_for_termination()
