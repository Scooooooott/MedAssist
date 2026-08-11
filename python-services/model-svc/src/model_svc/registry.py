from __future__ import annotations

from collections.abc import Iterable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from threading import Condition, RLock

from model_svc.core import EmbeddingBackend


class ModelSelectionError(ValueError):
    """Raised when a requested model identity is not registered exactly."""


class ModelNotServingError(RuntimeError):
    """Raised when an exact model cannot be loaded without fallback."""


@dataclass(frozen=True, slots=True)
class RegisteredEmbeddingModel:
    name: str
    version: str
    dimension: int
    backend: str
    model_path: str | None
    implementation: EmbeddingBackend
    enabled: bool = True

    @property
    def ready(self) -> bool:
        return self.implementation.ready

    @property
    def not_ready_reason(self) -> str | None:
        return self.implementation.not_ready_reason


@dataclass(slots=True)
class _RuntimeState:
    resident: bool = False
    loading: bool = False
    ref_count: int = 0
    last_used: int = 0
    ever_ready: bool = False
    load_failure: str | None = None


class EmbeddingModelRegistry:
    """Exact-identity registry with bounded, lease-protected model residency."""

    def __init__(
        self,
        models: Iterable[RegisteredEmbeddingModel],
        max_resident_models: int = 1,
    ) -> None:
        entries = tuple(models)
        if not entries:
            raise ValueError("at least one embedding model must be registered")
        if max_resident_models < 1:
            raise ValueError("max_resident_models must be positive")
        if not any(model.enabled for model in entries):
            raise ValueError("at least one embedding model must be enabled")
        keys: set[tuple[str, str]] = set()
        names: dict[str, list[RegisteredEmbeddingModel]] = {}
        for model in entries:
            if not model.name or not model.version:
                raise ValueError("embedding model name and version must be non-empty")
            if "@" in model.name:
                raise ValueError("embedding model names must not contain '@'")
            key = (model.name, model.version)
            if key in keys:
                raise ValueError(f"duplicate embedding model registration: {key!r}")
            if model.dimension < 1:
                raise ValueError("embedding model dimension must be positive")
            if model.implementation.model_name != model.name:
                raise ValueError("registered name does not match backend identity")
            if model.implementation.model_version != model.version:
                raise ValueError("registered version does not match backend identity")
            if model.implementation.dimension != model.dimension:
                raise ValueError("registered dimension does not match backend identity")
            keys.add(key)
            names.setdefault(model.name, []).append(model)

        self._models = entries
        self._by_identity = {(model.name, model.version): model for model in entries}
        self._by_name = {name: tuple(values) for name, values in names.items()}
        self._states = {key: _RuntimeState() for key in self._by_identity}
        self._default = next(model for model in entries if model.enabled)
        self._max_resident_models = max_resident_models
        self._condition = Condition(RLock())
        self._clock = 0

    @classmethod
    def from_backend(
        cls,
        backend: EmbeddingBackend,
        max_resident_models: int = 1,
    ) -> EmbeddingModelRegistry:
        """Wrap the legacy single backend API without changing its identity."""

        return cls(
            (
                RegisteredEmbeddingModel(
                    name=backend.model_name,
                    version=backend.model_version,
                    dimension=backend.dimension,
                    backend="legacy-single",
                    model_path=None,
                    implementation=backend,
                ),
            ),
            max_resident_models=max_resident_models,
        )

    @property
    def models(self) -> tuple[RegisteredEmbeddingModel, ...]:
        return self._models

    @property
    def enabled_models(self) -> tuple[RegisteredEmbeddingModel, ...]:
        return tuple(model for model in self._models if model.enabled)

    @property
    def default_model(self) -> RegisteredEmbeddingModel:
        return self._default

    @property
    def max_resident_models(self) -> int:
        return self._max_resident_models

    @property
    def resident_identities(self) -> tuple[str, ...]:
        with self._condition:
            return tuple(
                f"{model.name}@{model.version}"
                for model in self.enabled_models
                if self._states[(model.name, model.version)].resident
            )

    @property
    def ready(self) -> bool:
        key = (self._default.name, self._default.version)
        with self._condition:
            state = self._states[key]
            return state.ever_ready and state.load_failure is None

    @property
    def not_ready_reasons(self) -> tuple[str, ...]:
        key = (self._default.name, self._default.version)
        with self._condition:
            state = self._states[key]
            if self.ready:
                return ()
            reason = state.load_failure or self._default.not_ready_reason or "not ready"
            return (f"{self._default.name}@{self._default.version}: {reason}",)

    def warmup(self) -> bool:
        """Preload only the deterministic default (first enabled) model."""

        try:
            with self.lease(self._default):
                return True
        except ModelNotServingError:
            return False

    def resolve(self, name: str, version: str | None = None) -> RegisteredEmbeddingModel:
        if not name:
            raise ModelSelectionError("model name must not be empty")
        if version is None:
            raise ModelSelectionError(f"model version is required for {name}")
        model = self._by_identity.get((name, version))
        if model is None or not model.enabled:
            raise ModelSelectionError(f"embedding model {name}@{version} is not registered")
        return model

    def resolve_selector(self, selector: str) -> RegisteredEmbeddingModel:
        if not selector:
            enabled = self.enabled_models
            if len(enabled) == 1:
                return enabled[0]
            raise ModelSelectionError(
                "model name@version is required when multiple models are enabled"
            )
        name, separator, version = selector.rpartition("@")
        if separator:
            if not name or not version:
                raise ModelSelectionError("model selector must be name@version")
            return self.resolve(name, version)
        enabled = self.enabled_models
        if len(enabled) == 1 and enabled[0].name == selector:
            return enabled[0]
        return self.resolve(selector)

    @contextmanager
    def lease_selector(self, selector: str) -> Iterator[RegisteredEmbeddingModel]:
        with self.lease(self.resolve_selector(selector)) as model:
            yield model

    @contextmanager
    def lease(self, model: RegisteredEmbeddingModel) -> Iterator[RegisteredEmbeddingModel]:
        key = (model.name, model.version)
        self._acquire(key)
        try:
            yield model
        finally:
            self._release(key)

    def _acquire(self, key: tuple[str, str]) -> None:
        model = self._by_identity[key]
        while True:
            with self._condition:
                state = self._states[key]
                if state.resident:
                    state.ref_count += 1
                    self._touch(state)
                    return
                if state.loading:
                    self._condition.wait()
                    continue

                if self._occupied_slots() >= self._max_resident_models:
                    victim = self._least_recent_idle(excluding=key)
                    if victim is None:
                        self._condition.wait()
                        continue
                    victim_model = self._by_identity[victim]
                    victim_state = self._states[victim]
                    victim_state.resident = False
                    victim_state.load_failure = None
                    victim_model.implementation.unload()

                state.loading = True
                break

        try:
            loaded = model.implementation.warmup()
            reason = model.not_ready_reason or "warmup probe failed"
        except Exception as exc:  # noqa: BLE001 - model loading must fail closed.
            loaded = False
            reason = f"warmup failed: {type(exc).__name__}"

        with self._condition:
            state = self._states[key]
            state.loading = False
            if loaded and model.ready:
                state.resident = True
                state.ref_count = 1
                state.ever_ready = True
                state.load_failure = None
                self._touch(state)
                self._condition.notify_all()
                return
            state.resident = False
            state.load_failure = reason
            self._condition.notify_all()
        raise ModelNotServingError(f"embedding model {model.name}@{model.version} is NOT_SERVING")

    def _release(self, key: tuple[str, str]) -> None:
        with self._condition:
            state = self._states[key]
            if state.ref_count < 1:
                raise RuntimeError("embedding model lease underflow")
            state.ref_count -= 1
            self._touch(state)
            self._condition.notify_all()

    def _occupied_slots(self) -> int:
        return sum(state.resident or state.loading for state in self._states.values())

    def _least_recent_idle(self, excluding: tuple[str, str]) -> tuple[str, str] | None:
        candidates = (
            (key, state)
            for key, state in self._states.items()
            if key != excluding and state.resident and state.ref_count == 0 and not state.loading
        )
        return min(candidates, key=lambda item: item[1].last_used, default=(None, None))[0]

    def _touch(self, state: _RuntimeState) -> None:
        self._clock += 1
        state.last_used = self._clock
