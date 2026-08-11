from __future__ import annotations

from medassist_common import BaseServiceSettings
from pydantic import Field


class DeidSettings(BaseServiceSettings):
    service_name: str = "deid-svc"
    grpc_port: int = 9002
    hmac_salt: str = Field(default="", repr=False)
    presidio_model_name: str = "en_core_web_sm"
    presidio_model_version: str = "configured"
