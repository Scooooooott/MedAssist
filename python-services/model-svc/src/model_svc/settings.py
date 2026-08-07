from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import Field

from medassist_common import BaseServiceSettings


class ModelSettings(BaseServiceSettings):
    service_name: str = "model-svc"
    grpc_port: int = 9003
    backend: Literal["onnx-int8", "deterministic-test"] = "onnx-int8"
    allow_deterministic_test_backend: bool = False
    model_path: Path | None = None
    tokenizer_path: Path | None = None
    model_name: str = "BAAI/bge-m3"
    model_version: str = "unversioned"
    dimension: int = Field(default=1024, ge=1)
    max_length: int = Field(default=1024, ge=1, le=1024)
    batch_size: int = Field(default=8, ge=1)
    quantization: Literal["int8"] = "int8"
    query_prefix: str = ""
    passage_prefix: str = ""
