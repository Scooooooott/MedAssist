from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class BaseServiceSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="MEDASSIST_")

    service_name: str
    grpc_port: int
    grpc_workers: int = Field(default=4, ge=1)
    grpc_max_concurrent_rpcs: int = Field(default=32, ge=1)
    worker_threads: int = Field(default=2, ge=1)
    work_queue_capacity: int = Field(default=8, ge=0)
    runtime_intra_op_threads: int = Field(default=1, ge=1)
    runtime_inter_op_threads: int = Field(default=1, ge=1)
    metrics_port: int = Field(default=0, ge=0, le=65535)
    tracing_enabled: bool = True
    otlp_endpoint: str = "http://localhost:4317"
    otlp_insecure: bool = True
    tracing_sample_ratio: float = Field(default=1.0, ge=0.0, le=1.0)
    readiness_poll_seconds: float = Field(default=1.0, gt=0)
    shutdown_grace_seconds: int = Field(default=10, ge=1)
