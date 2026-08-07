from __future__ import annotations

import hashlib
import math
from pathlib import Path
from typing import Any, Literal, Protocol


EmbeddingInputType = Literal["query", "passage"]


class BackendNotReadyError(RuntimeError):
    """Raised when an embedding backend has not passed its warmup gate."""


class EmbeddingBackend(Protocol):
    model_name: str
    model_version: str
    dimension: int
    max_length: int

    @property
    def ready(self) -> bool: ...

    @property
    def not_ready_reason(self) -> str | None: ...

    def warmup(self) -> bool: ...

    def embed(self, texts: list[str], input_type: EmbeddingInputType) -> list[list[float]]: ...


class DeterministicEmbeddingModel:
    """Small deterministic backend intended only for explicit unit-test injection."""

    def __init__(
        self,
        dimension: int = 1024,
        model_name: str = "bge-m3-test",
        model_version: str = "test-deterministic",
        max_length: int = 1024,
    ) -> None:
        if dimension < 1:
            raise ValueError("dimension must be positive")
        if not 1 <= max_length <= 1024:
            raise ValueError("max_length must be between 1 and 1024")
        self.dimension = dimension
        self.model_name = model_name
        self.model_version = model_version
        self.max_length = max_length

    @property
    def ready(self) -> bool:
        return True

    @property
    def not_ready_reason(self) -> str | None:
        return None

    def warmup(self) -> bool:
        return True

    def embed(self, texts: list[str], input_type: EmbeddingInputType) -> list[list[float]]:
        if input_type not in ("query", "passage"):
            raise ValueError("input_type must be query or passage")
        return [self._embed_one(f"{input_type}:{text}") for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        values = [0.0 for _ in range(self.dimension)]
        for token in text.lower().split():
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimension
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            values[index] += sign
        norm = math.sqrt(sum(value * value for value in values)) or 1.0
        return [value / norm for value in values]


class OnnxBgeM3Backend:
    """BGE-M3 ONNX backend with an explicit int8 and warmup readiness gate.

    Imports for optional runtime dependencies are delayed until warmup so the
    process can expose NOT_SERVING with a useful reason when a model bundle or
    runtime is absent.
    """

    def __init__(
        self,
        model_path: str | Path | None,
        tokenizer_path: str | Path | None,
        model_name: str = "BAAI/bge-m3",
        model_version: str = "unversioned",
        dimension: int = 1024,
        max_length: int = 1024,
        batch_size: int = 8,
        quantization: str = "int8",
        query_prefix: str = "",
        passage_prefix: str = "",
    ) -> None:
        if dimension < 1:
            raise ValueError("dimension must be positive")
        if not 1 <= max_length <= 1024:
            raise ValueError("max_length must be between 1 and 1024")
        if batch_size < 1:
            raise ValueError("batch_size must be positive")
        if quantization.lower() != "int8":
            raise ValueError("the production backend only supports int8 ONNX models")

        self.model_path = Path(model_path) if model_path else None
        self.tokenizer_path = Path(tokenizer_path) if tokenizer_path else None
        self.model_name = model_name
        self.model_version = model_version
        self.dimension = dimension
        self.max_length = max_length
        self.batch_size = batch_size
        self.quantization = quantization.lower()
        self.query_prefix = query_prefix
        self.passage_prefix = passage_prefix
        self._session: Any | None = None
        self._tokenizer: Any | None = None
        self._not_ready_reason: str | None = "warmup has not completed"

    @property
    def ready(self) -> bool:
        return self._session is not None and self._tokenizer is not None and self._not_ready_reason is None

    @property
    def not_ready_reason(self) -> str | None:
        return self._not_ready_reason

    def warmup(self) -> bool:
        """Load the bundle and run one real inference before health is SERVING."""

        self._session = None
        self._tokenizer = None
        self._not_ready_reason = None
        if self.model_path is None or not self.model_path.is_file():
            self._not_ready_reason = f"ONNX int8 model file is missing: {self.model_path or '<unset>'}"
            return False
        if self.tokenizer_path is None or not self.tokenizer_path.is_file():
            self._not_ready_reason = f"tokenizer file is missing: {self.tokenizer_path or '<unset>'}"
            return False

        try:
            import numpy as np
            import onnxruntime as ort
            from tokenizers import Tokenizer

            session = ort.InferenceSession(
                str(self.model_path),
                providers=["CPUExecutionProvider"],
            )
            tokenizer = Tokenizer.from_file(str(self.tokenizer_path))
            self._session = session
            self._tokenizer = tokenizer
            warmup_vectors = self.embed(["model warmup"], "passage")
            if not warmup_vectors or len(warmup_vectors[0]) != self.dimension:
                raise ValueError(
                    f"model output dimension does not match configured dimension {self.dimension}"
                )
            if not np.isfinite(np.asarray(warmup_vectors, dtype=np.float32)).all():
                raise ValueError("model warmup produced non-finite values")
        except Exception as exc:  # noqa: BLE001 - readiness must fail closed for any load error.
            self._session = None
            self._tokenizer = None
            self._not_ready_reason = f"ONNX int8 warmup failed: {type(exc).__name__}: {exc}"
            return False
        return True

    def embed(self, texts: list[str], input_type: EmbeddingInputType) -> list[list[float]]:
        if not self.ready:
            raise BackendNotReadyError(self._not_ready_reason or "embedding backend is not ready")
        if input_type not in ("query", "passage"):
            raise ValueError("input_type must be query or passage")
        if not texts:
            return []

        output: list[list[float]] = []
        for offset in range(0, len(texts), self.batch_size):
            output.extend(self._embed_batch(texts[offset : offset + self.batch_size], input_type))
        return output

    def _embed_batch(self, texts: list[str], input_type: EmbeddingInputType) -> list[list[float]]:
        import numpy as np

        tokenizer = self._tokenizer
        session = self._session
        if tokenizer is None or session is None:
            raise BackendNotReadyError(self._not_ready_reason or "embedding backend is not ready")

        prefix = self.query_prefix if input_type == "query" else self.passage_prefix
        encodings = tokenizer.encode_batch([prefix + text for text in texts])
        max_tokens = min(
            self.max_length,
            max(len(encoding.ids) for encoding in encodings),
        )
        input_ids = np.zeros((len(encodings), max_tokens), dtype=np.int64)
        attention_mask = np.zeros((len(encodings), max_tokens), dtype=np.int64)
        token_type_ids = np.zeros((len(encodings), max_tokens), dtype=np.int64)
        for row, encoding in enumerate(encodings):
            ids = encoding.ids[:max_tokens]
            mask = encoding.attention_mask[:max_tokens]
            input_ids[row, : len(ids)] = ids
            attention_mask[row, : len(mask)] = mask
            if getattr(encoding, "type_ids", None):
                token_type_ids[row, : min(len(encoding.type_ids), max_tokens)] = encoding.type_ids[:max_tokens]

        feeds: dict[str, Any] = {}
        supported_names = {item.name for item in session.get_inputs()}
        if "input_ids" in supported_names:
            feeds["input_ids"] = input_ids
        if "attention_mask" in supported_names:
            feeds["attention_mask"] = attention_mask
        if "token_type_ids" in supported_names:
            feeds["token_type_ids"] = token_type_ids
        if not feeds or "input_ids" not in feeds:
            raise ValueError("ONNX model must expose an input_ids input")

        outputs = session.run(None, feeds)
        hidden = np.asarray(outputs[0])
        if hidden.ndim == 3:
            mask = attention_mask.astype(np.float32)[..., None]
            pooled = (hidden.astype(np.float32) * mask).sum(axis=1) / np.maximum(mask.sum(axis=1), 1.0)
        elif hidden.ndim == 2:
            pooled = hidden.astype(np.float32)
        else:
            raise ValueError(f"unsupported ONNX embedding output rank: {hidden.ndim}")
        if pooled.shape[1] != self.dimension:
            raise ValueError(
                f"ONNX output dimension {pooled.shape[1]} != configured dimension {self.dimension}"
            )
        norms = np.linalg.norm(pooled, axis=1, keepdims=True)
        normalized = pooled / np.maximum(norms, 1e-12)
        return normalized.astype(np.float32).tolist()
