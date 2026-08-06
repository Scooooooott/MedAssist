from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class BaseServiceSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="MEDASSIST_")

    service_name: str
    grpc_port: int
    grpc_workers: int = Field(default=4, ge=1)
    grpc_max_concurrent_rpcs: int = Field(default=32, ge=1)
    shutdown_grace_seconds: int = Field(default=10, ge=1)
