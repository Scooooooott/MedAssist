from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

import pytest
from model_svc.backend import build_embedding_registry
from model_svc.core import DeterministicEmbeddingModel
from model_svc.model_config import EmbeddingModelConfig
from model_svc.registry import (
    EmbeddingModelRegistry,
    ModelNotServingError,
    ModelSelectionError,
    RegisteredEmbeddingModel,
)
from model_svc.settings import ModelSettings
from pydantic import ValidationError


@dataclass
class TrackingBackend:
    model_name: str
    model_version: str
    dimension: int = 4
    max_length: int = 16
    fail_load: bool = False
    warmup_started: threading.Event | None = None
    allow_warmup: threading.Event | None = None
    warmup_calls: int = 0
    unload_calls: int = 0
    _ready: bool = field(default=False, init=False)
    _reason: str | None = field(default="not loaded", init=False)

    @property
    def ready(self) -> bool:
        return self._ready

    @property
    def not_ready_reason(self) -> str | None:
        return self._reason

    def warmup(self) -> bool:
        self.warmup_calls += 1
        if self.warmup_started is not None:
            self.warmup_started.set()
        if self.allow_warmup is not None:
            assert self.allow_warmup.wait(timeout=2)
        self._ready = not self.fail_load
        self._reason = None if self._ready else "synthetic load failure"
        return self._ready

    def unload(self) -> None:
        if not self._ready:
            raise AssertionError("only resident models may be unloaded")
        self.unload_calls += 1
        self._ready = False
        self._reason = "not resident"

    def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
        if not self._ready:
            raise RuntimeError("not ready")
        return [[float(index)] * self.dimension for index, _text in enumerate(texts)]


def _tracking_model(backend: TrackingBackend) -> RegisteredEmbeddingModel:
    return RegisteredEmbeddingModel(
        name=backend.model_name,
        version=backend.model_version,
        dimension=backend.dimension,
        backend="test",
        model_path=None,
        implementation=backend,
    )


def _model(name: str, version: str, dimension: int = 4) -> RegisteredEmbeddingModel:
    backend = DeterministicEmbeddingModel(
        dimension=dimension, model_name=name, model_version=version
    )
    return RegisteredEmbeddingModel(
        name=name,
        version=version,
        dimension=dimension,
        backend="deterministic-test",
        model_path=None,
        implementation=backend,
    )


def test_registry_requires_exact_identity_and_supports_selector() -> None:
    registry = EmbeddingModelRegistry((_model("medical", "2026-01-01"), _model("general", "v2")))

    assert registry.resolve("medical", "2026-01-01").dimension == 4
    assert registry.resolve_selector("general@v2").version == "v2"
    with pytest.raises(ModelSelectionError, match="not registered"):
        registry.resolve("medical", "wrong")
    with pytest.raises(ModelSelectionError, match="required"):
        registry.resolve_selector("medical")
    with pytest.raises(ModelSelectionError, match="required"):
        registry.resolve_selector("")


def test_registry_rejects_backend_identity_or_dimension_mismatch() -> None:
    backend = DeterministicEmbeddingModel(dimension=4, model_name="medical", model_version="v1")
    with pytest.raises(ValueError, match="dimension"):
        EmbeddingModelRegistry(
            (
                RegisteredEmbeddingModel(
                    name="medical",
                    version="v1",
                    dimension=5,
                    backend="deterministic-test",
                    model_path=None,
                    implementation=backend,
                ),
            )
        )


def test_startup_warms_only_first_enabled_model() -> None:
    first = TrackingBackend("first", "v1")
    second = TrackingBackend("second", "v1")
    registry = EmbeddingModelRegistry((_tracking_model(first), _tracking_model(second)))

    assert registry.warmup()
    assert first.warmup_calls == 1
    assert second.warmup_calls == 0
    assert registry.resident_identities == ("first@v1",)
    assert registry.ready


def test_on_demand_loading_honors_lru_and_resident_limit() -> None:
    first = TrackingBackend("first", "v1")
    second = TrackingBackend("second", "v1")
    third = TrackingBackend("third", "v1")
    registry = EmbeddingModelRegistry(
        tuple(_tracking_model(item) for item in (first, second, third)),
        max_resident_models=2,
    )

    assert registry.warmup()
    with registry.lease_selector("second@v1"):
        pass
    with registry.lease_selector("third@v1"):
        pass

    assert first.unload_calls == 1
    assert second.unload_calls == 0
    assert set(registry.resident_identities) == {"second@v1", "third@v1"}


def test_registry_never_evicts_a_model_with_an_active_lease() -> None:
    first = TrackingBackend("first", "v1")
    second = TrackingBackend("second", "v1")
    registry = EmbeddingModelRegistry(
        (_tracking_model(first), _tracking_model(second)), max_resident_models=1
    )
    acquired_second = threading.Event()

    def acquire_second() -> None:
        with registry.lease_selector("second@v1"):
            acquired_second.set()

    with registry.lease_selector("first@v1"):
        worker = threading.Thread(target=acquire_second)
        worker.start()
        time.sleep(0.05)
        assert not acquired_second.is_set()
        assert first.unload_calls == 0

    worker.join(timeout=2)
    assert not worker.is_alive()
    assert acquired_second.is_set()
    assert first.unload_calls == 1


def test_concurrent_requests_share_one_model_load() -> None:
    started = threading.Event()
    allow = threading.Event()
    backend = TrackingBackend("medical", "v1", warmup_started=started, allow_warmup=allow)
    registry = EmbeddingModelRegistry((_tracking_model(backend),))
    acquired = 0
    acquired_lock = threading.Lock()

    def acquire() -> None:
        nonlocal acquired
        with registry.lease_selector("medical@v1"):
            with acquired_lock:
                acquired += 1

    workers = [threading.Thread(target=acquire) for _ in range(2)]
    for worker in workers:
        worker.start()
    assert started.wait(timeout=2)
    allow.set()
    for worker in workers:
        worker.join(timeout=2)

    assert acquired == 2
    assert backend.warmup_calls == 1


def test_candidate_load_failure_is_isolated_and_does_not_fallback() -> None:
    default = TrackingBackend("default", "v1")
    failing = TrackingBackend("failing", "v1", fail_load=True)
    registry = EmbeddingModelRegistry(
        (_tracking_model(default), _tracking_model(failing)), max_resident_models=1
    )
    assert registry.warmup()

    with pytest.raises(ModelNotServingError, match="failing@v1"):
        with registry.lease_selector("failing@v1"):
            raise AssertionError("failed model must not yield a lease")

    assert failing.warmup_calls == 1
    assert registry.ready
    with registry.lease_selector("default@v1") as selected:
        assert selected.name == "default"


def test_default_load_failure_keeps_registry_not_serving() -> None:
    backend = TrackingBackend("default", "v1", fail_load=True)
    registry = EmbeddingModelRegistry((_tracking_model(backend),))

    assert not registry.warmup()
    assert not registry.ready
    assert "synthetic load failure" in registry.not_ready_reasons[0]


def test_three_candidate_settings_parse_from_json(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MEDASSIST_ALLOW_DETERMINISTIC_TEST_BACKEND", "true")
    monkeypatch.setenv("MEDASSIST_MAX_RESIDENT_EMBEDDING_MODELS", "2")
    monkeypatch.setenv(
        "MEDASSIST_EMBEDDING_MODELS",
        '[{"name":"medical","version":"med-v1","dimension":1024,"backend":"deterministic-test","model_path":"/models/medical.onnx"},{"name":"multilingual","version":"multi-v2","dimension":768,"backend":"deterministic-test","model_path":"/models/multi.onnx"},{"name":"light","version":"light-v3","dimension":1536,"backend":"deterministic-test","model_path":"/models/light.onnx"}]',
    )

    settings = ModelSettings()
    registry = build_embedding_registry(settings)

    assert [(model.name, model.version, model.dimension) for model in registry.models] == [
        ("medical", "med-v1", 1024),
        ("multilingual", "multi-v2", 768),
        ("light", "light-v3", 1536),
    ]
    assert registry.max_resident_models == 2
    assert registry.resident_identities == ()


def test_legacy_single_model_settings_remain_supported() -> None:
    settings = ModelSettings(
        backend="deterministic-test",
        allow_deterministic_test_backend=True,
        model_name="legacy",
        model_version="legacy-v1",
        dimension=4,
    )
    registry = build_embedding_registry(settings)
    assert registry.resolve_selector("").name == "legacy"


def test_resident_limit_must_be_positive() -> None:
    with pytest.raises(ValidationError):
        ModelSettings(max_resident_embedding_models=0)
    with pytest.raises(ValueError, match="positive"):
        EmbeddingModelRegistry((_model("medical", "v1"),), max_resident_models=0)


def test_config_rejects_floating_model_versions() -> None:
    with pytest.raises(ValueError, match="fixed"):
        EmbeddingModelConfig(
            name="medical",
            version="latest",
            dimension=1024,
            backend="deterministic-test",
            model_path=Path("/models/medical.onnx"),
        )


def test_config_requires_a_path_for_production_bundle() -> None:
    with pytest.raises(ValueError, match="model_path"):
        EmbeddingModelConfig.model_validate(
            {
                "name": "medical",
                "version": "med-v1",
                "dimension": 1024,
                "backend": "onnx-int8",
            }
        )
