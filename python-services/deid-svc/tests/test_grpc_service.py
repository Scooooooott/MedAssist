from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import Never

import grpc
import pytest
from medassist_common import BoundedExecutor, configure_generated_proto_path

configure_generated_proto_path()

from medassist.contracts.v1 import deid_pb2  # noqa: E402

from deid_svc.core import DeidResult, PhiEntity  # noqa: E402
from deid_svc.grpc_service import DeidService  # noqa: E402
from deid_svc.server import build_backend  # noqa: E402
from deid_svc.settings import DeidSettings  # noqa: E402


class Context:
    def abort(self, code: grpc.StatusCode, details: str) -> Never:
        raise grpc.RpcError(f"{code}: {details}")


class AbortedError(RuntimeError):
    def __init__(self, code: grpc.StatusCode, details: str) -> None:
        super().__init__(details)
        self.code = code
        self.details = details


class InspectingContext:
    def abort(self, code: grpc.StatusCode, details: str) -> Never:
        raise AbortedError(code, details)


@dataclass
class Backend:
    ready: bool = True
    policy_version: str = "fake-v1"

    def detect(self, text: str) -> list[PhiEntity]:
        return [PhiEntity("EMAIL", 0, len(text), 0.99, "fake")]

    def anonymize(
        self,
        text: str,
        policy: str = "SAFE_HARBOR_SURROGATE",
        document_key: str | None = None,
    ) -> DeidResult:
        replacement = "[EMAIL]" if policy == "SAFE_HARBOR_REDACT" else "EMAIL_SURROGATE"
        return DeidResult(replacement, self.detect(text), self.policy_version)


def test_detect_response_does_not_contain_original_value() -> None:
    response = DeidService(Backend()).Detect(
        deid_pb2.DetectRequest(text="alice@example.com"), Context()
    )

    assert response.entities[0].entity_type == "EMAIL"
    assert "alice@example.com" not in response.SerializeToString().decode("latin1", errors="ignore")


def test_anonymize_defaults_to_surrogate_and_supports_redact() -> None:
    service = DeidService(Backend())
    text = "alice@example.com"

    surrogate = service.Anonymize(deid_pb2.AnonymizeRequest(text=text), Context())
    redacted = service.Anonymize(
        deid_pb2.AnonymizeRequest(text=text, policy=deid_pb2.DEID_POLICY_SAFE_HARBOR_REDACT),
        Context(),
    )

    assert surrogate.text == "EMAIL_SURROGATE"
    assert redacted.text == "[EMAIL]"
    assert text not in surrogate.text
    assert text not in redacted.text


def test_unready_backend_fails_closed() -> None:
    with pytest.raises(grpc.RpcError):
        DeidService(Backend(ready=False)).Detect(deid_pb2.DetectRequest(text="secret"), Context())


def test_unknown_policy_is_rejected() -> None:
    with pytest.raises(grpc.RpcError):
        DeidService(Backend()).Anonymize(
            deid_pb2.AnonymizeRequest(text="secret", policy=99), Context()
        )


def test_server_backend_selection_is_explicit_and_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("MEDASSIST_DEID_TEST_MODE", "true")
    test_backend = build_backend(DeidSettings(hmac_salt="test-salt"))
    assert test_backend.ready

    monkeypatch.delenv("MEDASSIST_DEID_TEST_MODE")
    production_backend = build_backend(DeidSettings())
    assert not production_backend.ready


def test_overload_is_rejected_fail_closed() -> None:
    started = threading.Event()
    release = threading.Event()

    class BlockingBackend(Backend):
        def detect(self, text: str) -> list[PhiEntity]:
            started.set()
            release.wait(timeout=2)
            return super().detect(text)

    executor = BoundedExecutor(
        service_name="deid-svc",
        process_model="bounded_online_thread_pool",
        queue_name="deid-test",
        max_workers=1,
        queue_capacity=0,
    )
    service = DeidService(BlockingBackend(), executor)
    worker = threading.Thread(
        target=lambda: service.Detect(deid_pb2.DetectRequest(text="first"), Context())
    )
    worker.start()
    assert started.wait(timeout=1)
    try:
        with pytest.raises(AbortedError) as error:
            service.Detect(deid_pb2.DetectRequest(text="second"), InspectingContext())
        assert error.value.code == grpc.StatusCode.RESOURCE_EXHAUSTED
        assert "second" not in error.value.details
        assert service.readiness()
    finally:
        release.set()
        worker.join(timeout=2)
        executor.shutdown()
    assert not service.readiness()
