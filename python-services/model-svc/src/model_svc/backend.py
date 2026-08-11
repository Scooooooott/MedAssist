from __future__ import annotations

from model_svc.core import (
    DeterministicEmbeddingModel,
    EmbeddingBackend,
    OnnxBgeM3Backend,
    OnnxCrossEncoderReranker,
    RerankBackend,
)
from model_svc.model_config import EmbeddingModelConfig
from model_svc.registry import EmbeddingModelRegistry, RegisteredEmbeddingModel
from model_svc.settings import ModelSettings


def build_backend(settings: ModelSettings) -> EmbeddingBackend:
    """Construct the configured backend without silently degrading production."""

    if settings.backend == "deterministic-test":
        if not settings.allow_deterministic_test_backend:
            raise RuntimeError(
                "deterministic-test backend requires "
                "MEDASSIST_ALLOW_DETERMINISTIC_TEST_BACKEND=true"
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


def _build_embedding_backend(
    settings: ModelSettings, config: EmbeddingModelConfig
) -> EmbeddingBackend:
    if config.backend == "deterministic-test":
        if not settings.allow_deterministic_test_backend:
            raise RuntimeError(
                "deterministic-test embedding models require "
                "MEDASSIST_ALLOW_DETERMINISTIC_TEST_BACKEND=true"
            )
        return DeterministicEmbeddingModel(
            dimension=config.dimension,
            model_name=config.name,
            model_version=config.version,
            max_length=config.max_length,
        )
    return OnnxBgeM3Backend(
        model_path=config.model_path,
        tokenizer_path=config.tokenizer_path,
        model_name=config.name,
        model_version=config.version,
        dimension=config.dimension,
        max_length=config.max_length,
        batch_size=config.batch_size,
        quantization=config.quantization,
        query_prefix=config.query_prefix,
        passage_prefix=config.passage_prefix,
    )


def build_embedding_registry(settings: ModelSettings) -> EmbeddingModelRegistry:
    """Build a multi-model registry, retaining the legacy single-model config."""

    if not settings.embedding_models:
        # Preserve the legacy MEDASSIST_MODEL_* contract, including its existing
        # identity defaults. New multi-model declarations are strictly pinned.
        return EmbeddingModelRegistry.from_backend(
            build_backend(settings),
            max_resident_models=settings.max_resident_embedding_models,
        )
    configs = settings.embedding_models
    return EmbeddingModelRegistry(
        (
            RegisteredEmbeddingModel(
                name=config.name,
                version=config.version,
                dimension=config.dimension,
                backend=config.backend,
                model_path=str(config.model_path) if config.model_path else None,
                implementation=_build_embedding_backend(settings, config),
                enabled=config.enabled,
            )
            for config in configs
        ),
        max_resident_models=settings.max_resident_embedding_models,
    )


def build_reranker(settings: ModelSettings) -> RerankBackend:
    """Build the configured production reranker; never select a test fallback."""

    if settings.rerank_profile == "online":
        return OnnxCrossEncoderReranker(
            model_path=settings.rerank_online_model_path,
            tokenizer_path=settings.rerank_online_tokenizer_path,
            model_name=settings.rerank_online_model_name,
            model_version=settings.rerank_online_model_version,
            max_length=settings.rerank_online_max_length,
            batch_size=settings.rerank_online_batch_size,
        )
    return OnnxCrossEncoderReranker(
        model_path=settings.rerank_offline_model_path,
        tokenizer_path=settings.rerank_offline_tokenizer_path,
        model_name=settings.rerank_offline_model_name,
        model_version=settings.rerank_offline_model_version,
        max_length=settings.rerank_offline_max_length,
        batch_size=settings.rerank_offline_batch_size,
    )
