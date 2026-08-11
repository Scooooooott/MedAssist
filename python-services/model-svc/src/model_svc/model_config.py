from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class EmbeddingModelConfig(BaseModel):
    """One explicit, non-floating embedding model bundle declaration."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    name: str = Field(min_length=1)
    version: str = Field(min_length=1)
    dimension: int = Field(ge=1)
    backend: Literal["onnx-int8", "deterministic-test"]
    model_path: Path
    tokenizer_path: Path | None = None
    enabled: bool = True
    max_length: int = Field(default=1024, ge=1, le=1024)
    batch_size: int = Field(default=8, ge=1)
    quantization: Literal["int8"] = "int8"
    query_prefix: str = ""
    passage_prefix: str = ""

    @field_validator("name", "version")
    @classmethod
    def reject_floating_identity(cls, value: str) -> str:
        if value.strip() != value:
            raise ValueError("model name and version must not have surrounding whitespace")
        if value.lower() in {"latest", "default", "main", "stable", "unversioned"}:
            raise ValueError("model identity must use an explicit fixed name/version")
        return value
