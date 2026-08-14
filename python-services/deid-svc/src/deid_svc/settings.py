from __future__ import annotations

from medassist_common import BaseServiceSettings
from pydantic import Field


class DeidSettings(BaseServiceSettings):
    service_name: str = "deid-svc"
    grpc_port: int = 9002
    grpc_workers: int = Field(default=4, ge=1)
    grpc_max_concurrent_rpcs: int = Field(default=8, ge=1)
    worker_threads: int = Field(default=2, ge=1)
    work_queue_capacity: int = Field(default=4, ge=0)
    runtime_intra_op_threads: int = Field(default=1, ge=1)
    runtime_inter_op_threads: int = Field(default=1, ge=1)
    metrics_port: int = Field(default=9102, ge=0, le=65535)
    deid_test_mode: bool = False
    hmac_salt: str = Field(default="", repr=False)
    presidio_model_name: str = "en_core_web_sm"
    presidio_model_version: str = "configured"
