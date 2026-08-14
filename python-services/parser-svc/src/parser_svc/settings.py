from __future__ import annotations

from medassist_common import BaseServiceSettings
from pydantic import Field


class ParserSettings(BaseServiceSettings):
    service_name: str = "parser-svc"
    grpc_port: int = 9001
    grpc_workers: int = Field(default=4, ge=1)
    grpc_max_concurrent_rpcs: int = Field(default=4, ge=1)
    worker_threads: int = Field(default=2, ge=1)
    work_queue_capacity: int = Field(default=8, ge=0)
    runtime_intra_op_threads: int = Field(default=1, ge=1)
    runtime_inter_op_threads: int = Field(default=1, ge=1)
    metrics_port: int = Field(default=9101, ge=0, le=65535)
    s3_endpoint_url: str | None = None
    s3_region: str = "us-east-1"
    s3_access_key_id: str | None = None
    s3_secret_access_key: str | None = None
    s3_session_token: str | None = None
    s3_force_path_style: bool = True
    pdf_backend: str = "docling"
    pdf_timeout_seconds: float = Field(default=120.0, gt=0)
