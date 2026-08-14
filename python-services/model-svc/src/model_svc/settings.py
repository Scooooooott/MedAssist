from __future__ import annotations

from pathlib import Path
from typing import Literal

from medassist_common import BaseServiceSettings
from pydantic import Field

from model_svc.model_config import EmbeddingModelConfig


class ModelSettings(BaseServiceSettings):
    service_name: str = "model-svc"
    grpc_port: int = 9003
    grpc_workers: int = Field(default=4, ge=1)
    grpc_max_concurrent_rpcs: int = Field(default=12, ge=1)
    worker_threads: int = Field(default=2, ge=1)
    work_queue_capacity: int = Field(default=8, ge=0)
    query_worker_threads: int = Field(default=2, ge=1)
    runtime_intra_op_threads: int = Field(default=1, ge=1)
    runtime_inter_op_threads: int = Field(default=1, ge=1)
    metrics_port: int = Field(default=9103, ge=0, le=65535)
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
    embedding_models: tuple[EmbeddingModelConfig, ...] = ()
    max_resident_embedding_models: int = Field(default=1, ge=1)
    rerank_enabled: bool = False
    rerank_profile: Literal["online", "offline"] = "online"
    rerank_online_model_path: Path | None = None
    rerank_online_tokenizer_path: Path | None = None
    rerank_online_model_name: str = "cross-encoder/ms-marco-MiniLM-L-6-v2"
    rerank_online_model_version: str = "unversioned"
    rerank_online_max_length: int = Field(default=512, ge=1, le=1024)
    rerank_online_batch_size: int = Field(default=8, ge=1)
    rerank_offline_model_path: Path | None = None
    rerank_offline_tokenizer_path: Path | None = None
    rerank_offline_model_name: str = "BAAI/bge-reranker-v2-m3"
    rerank_offline_model_version: str = "unversioned"
    rerank_offline_max_length: int = Field(default=512, ge=1, le=1024)
    rerank_offline_batch_size: int = Field(default=8, ge=1)
    rerank_max_candidates: int = Field(default=100, ge=1, le=1000)
