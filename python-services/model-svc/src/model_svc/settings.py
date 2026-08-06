from __future__ import annotations

from medassist_common import BaseServiceSettings


class ModelSettings(BaseServiceSettings):
    service_name: str = "model-svc"
    grpc_port: int = 9003
