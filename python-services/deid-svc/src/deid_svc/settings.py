from __future__ import annotations

from medassist_common import BaseServiceSettings


class DeidSettings(BaseServiceSettings):
    service_name: str = "deid-svc"
    grpc_port: int = 9002
