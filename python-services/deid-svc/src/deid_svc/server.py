from __future__ import annotations

import logging
import os

from medassist_common import configure_generated_proto_path, configure_logging, serve_health
from deid_svc.core import FailClosedDeidentifier, RegexDeidentifier, build_production_deidentifier
from deid_svc.grpc_service import DeidService, deid_pb2_grpc
from deid_svc.settings import DeidSettings

configure_generated_proto_path()

LOGGER = logging.getLogger(__name__)


def build_backend(settings: DeidSettings):
    """Build production Presidio or an explicitly selected test backend.

    Initialization failures intentionally leave the process alive but NOT_SERVING so
    callers cannot mistake an unavailable analyzer for successfully de-identified text.
    """

    if os.getenv("MEDASSIST_DEID_TEST_MODE", "false").lower() == "true":
        return RegexDeidentifier(settings.hmac_salt or "test-only-salt")
    try:
        return build_production_deidentifier(settings)
    except Exception as exc:  # noqa: BLE001 - startup must fail closed.
        LOGGER.error("de-identification backend initialization failed", exc_info=exc)
        return FailClosedDeidentifier()


def main() -> None:
    settings = DeidSettings()
    configure_logging(settings.service_name)
    backend = build_backend(settings)
    service = DeidService(backend)
    serve_health(
        settings,
        register_servicers=lambda server: deid_pb2_grpc.add_DeidServiceServicer_to_server(
            service, server
        ),
        readiness=lambda: backend.ready,
    )


if __name__ == "__main__":
    main()
