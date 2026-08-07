from __future__ import annotations

from typing import Any

import grpc

from medassist_common import configure_generated_proto_path

configure_generated_proto_path()

from medassist.contracts.v1 import model_pb2, model_pb2_grpc  # noqa: E402

from model_svc.core import BackendNotReadyError, EmbeddingBackend  # noqa: E402


class ModelService(model_pb2_grpc.ModelServiceServicer):
    """gRPC adapter for the model backend; readiness is enforced per request."""

    def __init__(self, backend: EmbeddingBackend) -> None:
        self.backend = backend

    def Embed(self, request: Any, context: grpc.ServicerContext[Any, Any]) -> Any:
        if not self.backend.ready:
            context.abort(
                grpc.StatusCode.FAILED_PRECONDITION,
                self.backend.not_ready_reason or "embedding backend is not ready",
            )

        if request.model_name and request.model_name != self.backend.model_name:
            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"requested model {request.model_name!r} does not match {self.backend.model_name!r}",
            )

        input_type = self._input_type(request.input_type, context)
        try:
            vectors = self.backend.embed(list(request.texts), input_type)
        except BackendNotReadyError as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except ValueError as exc:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
        except Exception as exc:  # noqa: BLE001 - do not expose backend internals to clients.
            context.abort(grpc.StatusCode.INTERNAL, f"embedding inference failed: {type(exc).__name__}")

        return model_pb2.EmbedResponse(
            vectors=[model_pb2.FloatVector(values=vector) for vector in vectors],
            model_name=self.backend.model_name,
            model_version=self.backend.model_version,
            dimension=self.backend.dimension,
        )

    def Rerank(self, request: Any, context: grpc.ServicerContext[Any, Any]) -> Any:
        del request
        context.abort(grpc.StatusCode.UNIMPLEMENTED, "Rerank is not implemented in M1.4")

    @staticmethod
    def _input_type(value: int, context: grpc.ServicerContext[Any, Any]) -> str:
        if value == model_pb2.EMBEDDING_INPUT_TYPE_QUERY:
            return "query"
        if value == model_pb2.EMBEDDING_INPUT_TYPE_PASSAGE:
            return "passage"
        context.abort(
            grpc.StatusCode.INVALID_ARGUMENT,
            "input_type must be EMBEDDING_INPUT_TYPE_QUERY or EMBEDDING_INPUT_TYPE_PASSAGE",
        )
        raise AssertionError("context.abort must terminate the request")
