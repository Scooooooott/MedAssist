from __future__ import annotations

from medassist_common import BaseServiceSettings


class ParserSettings(BaseServiceSettings):
    service_name: str = "parser-svc"
    grpc_port: int = 9001
