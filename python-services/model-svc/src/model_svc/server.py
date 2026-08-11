from __future__ import annotations

import logging

from medassist_common import (
    configure_generated_proto_path,
    configure_logging,
    serve_health,
)

from model_svc.backend import build_embedding_registry, build_reranker
from model_svc.grpc_service import ModelService, model_pb2_grpc  # type: ignore[attr-defined]
from model_svc.settings import ModelSettings

configure_generated_proto_path()

LOGGER = logging.getLogger(__name__)


def main() -> None:
    settings = ModelSettings()
    configure_logging(settings.service_name)
    registry = build_embedding_registry(settings)
    reranker = build_reranker(settings) if settings.rerank_enabled else None
    embedding_ready = registry.warmup()
    if not embedding_ready:
        LOGGER.error("embedding registry is NOT_SERVING: %s", "; ".join(registry.not_ready_reasons))
    reranker_ready = True
    if reranker is not None:
        reranker_ready = reranker.warmup()
        if not reranker_ready:
            LOGGER.error("reranker backend is NOT_SERVING: %s", reranker.not_ready_reason)

    service = ModelService(registry, reranker, settings.rerank_max_candidates)
    serve_health(
        settings,
        register_servicers=lambda server: model_pb2_grpc.add_ModelServiceServicer_to_server(
            service, server
        ),
        readiness=lambda: registry.ready and (reranker is None or reranker.ready),
    )


if __name__ == "__main__":
    main()
