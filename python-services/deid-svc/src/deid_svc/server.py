from __future__ import annotations

import logging

from medassist_common import (
    BoundedExecutor,
    SafeTracer,
    ServiceMetrics,
    apply_runtime_thread_settings,
    configure_generated_proto_path,
    configure_logging,
    serve_health,
)

from deid_svc.core import (
    Deidentifier,
    FailClosedDeidentifier,
    RegexDeidentifier,
    build_production_deidentifier,
)
from deid_svc.grpc_service import DeidService, deid_pb2_grpc
from deid_svc.settings import DeidSettings

configure_generated_proto_path()

LOGGER = logging.getLogger(__name__)
PROCESS_MODEL = "bounded_online_thread_pool"


def build_backend(settings: DeidSettings) -> Deidentifier:
    """Build production Presidio or an explicitly selected test backend.

    Initialization failures intentionally leave the process alive but NOT_SERVING so
    callers cannot mistake an unavailable analyzer for successfully de-identified text.
    """

    if settings.deid_test_mode:
        return RegexDeidentifier(settings.hmac_salt or "test-only-salt")
    try:
        return build_production_deidentifier(settings)
    except Exception as exc:  # noqa: BLE001 - startup must fail closed.
        LOGGER.error("de-identification backend initialization failed", exc_info=exc)
        return FailClosedDeidentifier()


def main() -> None:
    settings = DeidSettings()
    configure_logging(settings.service_name)
    apply_runtime_thread_settings(settings)
    backend = build_backend(settings)
    metrics = ServiceMetrics(settings.service_name, PROCESS_MODEL)
    executor = BoundedExecutor(
        service_name=settings.service_name,
        process_model=PROCESS_MODEL,
        queue_name="deid",
        max_workers=settings.worker_threads,
        queue_capacity=settings.work_queue_capacity,
        metrics=metrics,
        tracer=SafeTracer(settings.service_name),
    )
    service = DeidService(backend, executor)
    serve_health(
        settings,
        register_servicers=lambda server: deid_pb2_grpc.add_DeidServiceServicer_to_server(
            service, server
        ),
        readiness=service.readiness,
        process_model=PROCESS_MODEL,
        on_shutdown=executor.shutdown,
    )


if __name__ == "__main__":
    main()
