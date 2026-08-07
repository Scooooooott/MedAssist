from __future__ import annotations

from model_svc.core import DeterministicEmbeddingModel, EmbeddingBackend, OnnxBgeM3Backend
from model_svc.settings import ModelSettings


def build_backend(settings: ModelSettings) -> EmbeddingBackend:
    """Construct the configured backend without silently degrading production."""

    if settings.backend == "deterministic-test":
        if not settings.allow_deterministic_test_backend:
            raise RuntimeError(
                "deterministic-test backend requires MEDASSIST_MODEL_ALLOW_DETERMINISTIC_TEST_BACKEND=true"
            )
        return DeterministicEmbeddingModel(
            dimension=settings.dimension,
            model_name=settings.model_name,
            model_version=settings.model_version,
            max_length=settings.max_length,
        )
    return OnnxBgeM3Backend(
        model_path=settings.model_path,
        tokenizer_path=settings.tokenizer_path,
        model_name=settings.model_name,
        model_version=settings.model_version,
        dimension=settings.dimension,
        max_length=settings.max_length,
        batch_size=settings.batch_size,
        quantization=settings.quantization,
        query_prefix=settings.query_prefix,
        passage_prefix=settings.passage_prefix,
    )
