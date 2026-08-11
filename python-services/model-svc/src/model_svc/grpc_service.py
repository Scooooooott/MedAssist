from __future__ import annotations

import math
from typing import Any

import grpc  # type: ignore[import-untyped]
from medassist_common import configure_generated_proto_path

configure_generated_proto_path()

from medassist.contracts.v1 import model_pb2, model_pb2_grpc  # type: ignore[import-not-found]  # noqa: E402, I001

from model_svc.core import (  # noqa: E402
    BackendNotReadyError,
    EmbeddingBackend,
    EmbeddingInputType,
    RerankBackend,
)
from model_svc.registry import (  # noqa: E402
    EmbeddingModelRegistry,
    ModelNotServingError,
    ModelSelectionError,
)


class ModelService(model_pb2_grpc.ModelServiceServicer):  # type: ignore[misc]
    """gRPC adapter for the model backend; readiness is enforced per request."""

    def __init__(
        self,
        backend: EmbeddingBackend | EmbeddingModelRegistry,
        reranker: RerankBackend | None = None,
        max_rerank_candidates: int = 100,
    ) -> None:
        if max_rerank_candidates < 1:
            raise ValueError("max_rerank_candidates must be positive")
        self.registry = (
            backend
            if isinstance(backend, EmbeddingModelRegistry)
            else EmbeddingModelRegistry.from_backend(backend)
        )
        self.backend = backend
        self.reranker = reranker
        self.max_rerank_candidates = max_rerank_candidates

    def Embed(  # noqa: ANN401, N802
        self,
        request: Any,  # noqa: ANN401
        context: grpc.ServicerContext[Any, Any],
    ) -> Any:  # noqa: ANN401
        selector = request.model_name
        input_type = self._input_type(request.input_type, context)
        try:
            with self.registry.lease_selector(selector) as model:
                vectors = model.implementation.embed(list(request.texts), input_type)
        except ModelSelectionError as exc:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            raise AssertionError("context.abort must terminate the request") from None
        except ModelNotServingError as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except BackendNotReadyError as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except ValueError as exc:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
        except Exception as exc:  # noqa: BLE001 - do not expose backend internals to clients.
            context.abort(
                grpc.StatusCode.INTERNAL,
                f"embedding inference failed: {type(exc).__name__}",
            )

        if len(vectors) != len(request.texts):
            context.abort(
                grpc.StatusCode.INTERNAL,
                "embedding backend returned an invalid vector count",
            )
        if (
            model.implementation.model_name != model.name
            or model.implementation.model_version != model.version
            or model.implementation.dimension != model.dimension
            or not self._vectors_are_valid(vectors, model.dimension)
        ):
            context.abort(
                grpc.StatusCode.INTERNAL,
                "embedding backend returned invalid vector dimensions",
            )

        return model_pb2.EmbedResponse(
            vectors=[model_pb2.FloatVector(values=vector) for vector in vectors],
            model_name=model.name,
            model_version=model.version,
            dimension=model.dimension,
        )

    def Rerank(  # noqa: ANN401, N802
        self,
        request: Any,  # noqa: ANN401
        context: grpc.ServicerContext[Any, Any],
    ) -> Any:  # noqa: ANN401
        reranker = self.reranker
        if reranker is None:
            context.abort(grpc.StatusCode.UNIMPLEMENTED, "reranker backend is not enabled")
            raise AssertionError("context.abort must terminate the request")
        if not reranker.ready:
            context.abort(
                grpc.StatusCode.FAILED_PRECONDITION,
                reranker.not_ready_reason or "reranker backend is not ready",
            )
        if request.model_name and request.model_name != reranker.model_name:
            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"requested model {request.model_name!r} does not match {reranker.model_name!r}",
            )
        if not request.query.strip():
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "query must not be empty")
        if len(request.candidates) > self.max_rerank_candidates:
            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"candidate count exceeds configured limit {self.max_rerank_candidates}",
            )

        candidates = [(candidate.id, candidate.text) for candidate in request.candidates]
        try:
            scores = reranker.rerank(request.query, candidates)
        except BackendNotReadyError as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except ValueError as exc:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
        except Exception as exc:  # noqa: BLE001 - do not expose backend internals to clients.
            context.abort(
                grpc.StatusCode.INTERNAL,
                f"rerank inference failed: {type(exc).__name__}",
            )

        if len(scores) != len(candidates):
            context.abort(grpc.StatusCode.INTERNAL, "reranker returned an invalid score count")
        if not all(math.isfinite(score) for score in scores):
            context.abort(grpc.StatusCode.INTERNAL, "reranker returned non-finite scores")
        ranked = sorted(
            zip(candidates, scores, strict=True),
            key=lambda item: item[1],
            reverse=True,
        )
        return model_pb2.RerankResponse(
            results=[
                model_pb2.RerankResult(id=candidate[0], score=score, rank=rank)
                for rank, (candidate, score) in enumerate(ranked, start=1)
            ],
            model_name=reranker.model_name,
            model_version=reranker.model_version,
        )

    @staticmethod
    def _vectors_are_valid(vectors: list[list[float]], dimension: int) -> bool:
        try:
            return all(
                len(vector) == dimension and all(math.isfinite(value) for value in vector)
                for vector in vectors
            )
        except (TypeError, ValueError):
            return False

    @staticmethod
    def _input_type(value: int, context: grpc.ServicerContext[Any, Any]) -> EmbeddingInputType:
        if value == model_pb2.EMBEDDING_INPUT_TYPE_QUERY:
            return "query"
        if value == model_pb2.EMBEDDING_INPUT_TYPE_PASSAGE:
            return "passage"
        context.abort(
            grpc.StatusCode.INVALID_ARGUMENT,
            "input_type must be EMBEDDING_INPUT_TYPE_QUERY or EMBEDDING_INPUT_TYPE_PASSAGE",
        )
        raise AssertionError("context.abort must terminate the request")
