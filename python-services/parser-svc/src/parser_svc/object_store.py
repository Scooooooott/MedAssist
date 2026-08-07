from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import unquote, urlparse

from parser_svc.settings import ParserSettings


class ObjectStoreError(RuntimeError):
    """A safe, user-facing object storage failure."""

    def __init__(self, code: str, message: str, *, attribute: tuple[str, str] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.attribute = attribute


@dataclass(frozen=True)
class StoredObject:
    payload: bytes
    content_type: str = ""


class S3ObjectStore:
    """Read objects from S3 or an S3-compatible MinIO endpoint.

    The parser contract intentionally exposes no byte-stream input. URI parsing
    is kept here so local paths and arbitrary schemes are rejected before any
    client call is made.
    """

    _supported_schemes = frozenset({"s3", "minio"})

    def __init__(self, settings: ParserSettings, client: Any | None = None) -> None:
        self._settings = settings
        self._client = client

    def read(self, storage_uri: str) -> StoredObject:
        bucket, key = self._parse_uri(storage_uri)
        client = self._client or self._build_client()
        try:
            response = client.get_object(Bucket=bucket, Key=key)
            body = response["Body"].read()
        except Exception as exc:
            raise ObjectStoreError("STORAGE_READ_FAILED", "unable to read the storage object") from exc
        if not isinstance(body, bytes) or not body:
            raise ObjectStoreError("STORAGE_OBJECT_EMPTY", "storage object is empty")
        content_type = str(response.get("ContentType") or "")
        return StoredObject(payload=body, content_type=content_type)

    @classmethod
    def _parse_uri(cls, storage_uri: str) -> tuple[str, str]:
        parsed = urlparse(storage_uri)
        if parsed.scheme.lower() not in cls._supported_schemes:
            raise ObjectStoreError(
                "INVALID_STORAGE_URI",
                "storage_uri must reference an s3:// or minio:// object",
                attribute=("scheme", parsed.scheme.lower() or "missing"),
            )
        bucket = parsed.netloc.strip()
        key = unquote(parsed.path.lstrip("/"))
        if not bucket or not key or parsed.params:
            raise ObjectStoreError("INVALID_STORAGE_URI", "storage_uri must include bucket and object key")
        return bucket, key

    def _build_client(self) -> Any:
        try:
            import boto3
            from botocore.config import Config
        except ImportError as exc:
            raise ObjectStoreError(
                "STORAGE_BACKEND_UNAVAILABLE",
                "boto3 is required to read S3 or MinIO objects",
            ) from exc

        kwargs: dict[str, Any] = {
            "region_name": self._settings.s3_region,
            "endpoint_url": self._settings.s3_endpoint_url,
        }
        if self._settings.s3_access_key_id:
            kwargs["aws_access_key_id"] = self._settings.s3_access_key_id
        if self._settings.s3_secret_access_key:
            kwargs["aws_secret_access_key"] = self._settings.s3_secret_access_key
        if self._settings.s3_session_token:
            kwargs["aws_session_token"] = self._settings.s3_session_token
        if self._settings.s3_force_path_style:
            kwargs["config"] = Config(s3={"addressing_style": "path"})
        self._client = boto3.client("s3", **kwargs)
        return self._client
