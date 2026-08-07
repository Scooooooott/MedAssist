from __future__ import annotations

from medassist_common import BaseServiceSettings


class ParserSettings(BaseServiceSettings):
    service_name: str = "parser-svc"
    grpc_port: int = 9001
    s3_endpoint_url: str | None = None
    s3_region: str = "us-east-1"
    s3_access_key_id: str | None = None
    s3_secret_access_key: str | None = None
    s3_session_token: str | None = None
    s3_force_path_style: bool = True
    pdf_backend: str = "docling"
