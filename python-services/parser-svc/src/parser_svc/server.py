from __future__ import annotations

import grpc
from medassist_common import (
    BoundedExecutor,
    SafeTracer,
    ServiceMetrics,
    apply_runtime_thread_settings,
    configure_logging,
    serve_health,
)

from parser_svc.pdf import PdfBackendError, build_pdf_backend
from parser_svc.service import ParserServiceServicer, build_parser_service
from parser_svc.settings import ParserSettings

PROCESS_MODEL = "bounded_offline_thread_pool"


def create_parser_service(
    settings: ParserSettings,
    executor: BoundedExecutor | None = None,
) -> ParserServiceServicer:
    try:
        pdf_backend = build_pdf_backend(settings)
    except PdfBackendError:
        # Keep the process available for TXT/MD/HTML while PDF calls report a
        # precise backend error through ParseDocument.
        pdf_backend = None
    return build_parser_service(settings, pdf_backend=pdf_backend, executor=executor)


def register_parser_servicers(
    server: grpc.Server,
    service: ParserServiceServicer,
) -> None:
    from medassist_common import configure_generated_proto_path

    configure_generated_proto_path()
    from medassist.contracts.v1 import parser_pb2_grpc

    parser_pb2_grpc.add_ParserServiceServicer_to_server(service, server)


def main() -> None:
    settings = ParserSettings()
    configure_logging(settings.service_name)
    apply_runtime_thread_settings(settings)
    metrics = ServiceMetrics(settings.service_name, PROCESS_MODEL)
    executor = BoundedExecutor(
        service_name=settings.service_name,
        process_model=PROCESS_MODEL,
        queue_name="parse",
        max_workers=settings.worker_threads,
        queue_capacity=settings.work_queue_capacity,
        metrics=metrics,
        tracer=SafeTracer(settings.service_name),
    )
    service = create_parser_service(settings, executor)
    serve_health(
        settings,
        register_servicers=lambda server: register_parser_servicers(server, service),
        readiness=service.readiness,
        process_model=PROCESS_MODEL,
        on_shutdown=executor.shutdown,
    )


if __name__ == "__main__":
    main()
