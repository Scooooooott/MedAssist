from __future__ import annotations

import logging

from medassist_common import configure_generated_proto_path, configure_logging, serve_health
from model_svc.backend import build_backend
from model_svc.grpc_service import ModelService, model_pb2_grpc
from model_svc.settings import ModelSettings

configure_generated_proto_path()

LOGGER = logging.getLogger(__name__)


def main() -> None:
    settings = ModelSettings()
    configure_logging(settings.service_name)
    backend = build_backend(settings)
    ready = backend.warmup()
    if not ready:
        LOGGER.error("model backend is NOT_SERVING: %s", backend.not_ready_reason)

    service = ModelService(backend)
    serve_health(
        settings,
        register_servicers=lambda server: model_pb2_grpc.add_ModelServiceServicer_to_server(
            service, server
        ),
        readiness=lambda: backend.ready,
    )


if __name__ == "__main__":
    main()
