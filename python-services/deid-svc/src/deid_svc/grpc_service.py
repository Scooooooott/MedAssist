from __future__ import annotations

from typing import Any

import grpc
from medassist_common import configure_generated_proto_path

configure_generated_proto_path()

from medassist.contracts.v1 import deid_pb2, deid_pb2_grpc  # noqa: E402

from deid_svc.core import (  # noqa: E402
    Deidentifier,
    DeidError,
    DeidUnavailableError,
    PhiEntity,
)

__all__ = ["DeidService", "deid_pb2_grpc"]


class DeidService(deid_pb2_grpc.DeidServiceServicer):  # type: ignore[misc]
    """gRPC boundary for the fail-closed de-identification backend."""

    def __init__(self, backend: Deidentifier) -> None:
        self._backend = backend

    def Detect(  # noqa: N802 - generated gRPC method name
        self,
        request: deid_pb2.DetectRequest,
        context: grpc.ServicerContext[Any, Any],
    ) -> deid_pb2.DetectResponse:
        self._require_ready(context)
        try:
            entities = self._backend.detect(request.text)
        except DeidUnavailableError as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except DeidError:
            context.abort(grpc.StatusCode.INTERNAL, "de-identification failed")
        except Exception as exc:  # noqa: BLE001 - do not expose backend details or text.
            context.abort(
                grpc.StatusCode.INTERNAL,
                f"de-identification failed: {type(exc).__name__}",
            )
        return deid_pb2.DetectResponse(
            entities=[self._entity_to_proto(entity) for entity in entities],
            policy_version=self._backend.policy_version,
        )

    def Anonymize(  # noqa: N802 - generated gRPC method name
        self,
        request: deid_pb2.AnonymizeRequest,
        context: grpc.ServicerContext[Any, Any],
    ) -> deid_pb2.AnonymizeResponse:
        self._require_ready(context)
        policy = self._policy(request.policy, context)
        document_key = request.options.get("document_key") or None
        try:
            result = self._backend.anonymize(request.text, policy, document_key)
        except DeidUnavailableError as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except DeidError:
            context.abort(grpc.StatusCode.INTERNAL, "de-identification failed")
        except Exception as exc:  # noqa: BLE001 - do not expose backend details or text.
            context.abort(
                grpc.StatusCode.INTERNAL,
                f"de-identification failed: {type(exc).__name__}",
            )
        return deid_pb2.AnonymizeResponse(
            text=result.text,
            entities=[self._entity_to_proto(entity) for entity in result.entities],
            policy_version=result.policy_version,
        )

    def _require_ready(self, context: grpc.ServicerContext[Any, Any]) -> None:
        if not self._backend.ready:
            context.abort(
                grpc.StatusCode.FAILED_PRECONDITION,
                "de-identification backend is not ready",
            )

    @staticmethod
    def _policy(value: int, context: grpc.ServicerContext[Any, Any]) -> str:
        if value in (deid_pb2.DEID_POLICY_UNSPECIFIED, deid_pb2.DEID_POLICY_SAFE_HARBOR_SURROGATE):
            return "SAFE_HARBOR_SURROGATE"
        if value == deid_pb2.DEID_POLICY_SAFE_HARBOR_REDACT:
            return "SAFE_HARBOR_REDACT"
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, "unsupported de-identification policy")
        raise AssertionError("context.abort must terminate the request")

    @staticmethod
    def _entity_to_proto(entity: PhiEntity) -> deid_pb2.PhiEntity:
        return deid_pb2.PhiEntity(
            entity_type=entity.entity_type,
            start=entity.start,
            end=entity.end,
            score=entity.score,
            recognizer=entity.recognizer,
        )
