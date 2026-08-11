from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import json
import mimetypes
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile
from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen
from urllib.robotparser import RobotFileParser


ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "data"
MANIFEST_SCHEMA_VERSION = "1.0"
DEFAULT_USER_AGENT = "MedAssist-data-fetcher/1.0 (+https://github.com/Scooooooott/MedAssist)"
SYNTHEA_DEFAULT_VERSION = "3.3.0"
SYNTHEA_DEFAULT_SEED = 20260806
SYNTHEA_PATIENT_COUNT = 1000


class FetchError(RuntimeError):
    """Base error for an actionable data workflow failure."""


class ConfigurationError(FetchError):
    pass


class GateError(FetchError):
    pass


class ValidationError(FetchError):
    pass


@dataclass(frozen=True)
class LicenseGate:
    status: str
    redistribution_allowed: bool
    third_party_llm_allowed: bool
    approval_reference: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "redistribution_allowed": self.redistribution_allowed,
            "third_party_llm_allowed": self.third_party_llm_allowed,
            "approval_reference": self.approval_reference,
        }


@dataclass(frozen=True)
class FetchPolicy:
    max_bytes: int = 100 * 1024 * 1024
    rate_limit_seconds: float = 1.0
    retries: int = 3
    backoff_seconds: float = 1.0
    timeout_seconds: float = 30.0
    user_agent: str = DEFAULT_USER_AGENT
    allowed_content_types: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["allowed_content_types"] = list(self.allowed_content_types)
        return value


@dataclass(frozen=True)
class SourceSpec:
    source_id: str
    fetcher: str
    url: str
    version: str
    planned_scale: str
    output_subdir: str
    license: LicenseGate
    policy: FetchPolicy = field(default_factory=FetchPolicy)
    credential_env: tuple[str, ...] = ()
    third_party_llm_note: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "source_id": self.source_id,
            "fetcher": self.fetcher,
            "url": self.url,
            "version": self.version,
            "planned_scale": self.planned_scale,
            "output_subdir": self.output_subdir,
            "license": self.license.as_dict(),
            "policy": self.policy.as_dict(),
            "credential_env": list(self.credential_env),
            "third_party_llm_note": self.third_party_llm_note,
        }


@dataclass(frozen=True)
class SyntheaConfig:
    patient_count: int = SYNTHEA_PATIENT_COUNT
    seed: int = SYNTHEA_DEFAULT_SEED
    version: str = SYNTHEA_DEFAULT_VERSION
    command_template: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class FetchContext:
    output_dir: Path
    dry_run: bool = False
    resume: bool = False
    force: bool = False
    synthea: SyntheaConfig = field(default_factory=SyntheaConfig)
    environ: Mapping[str, str] | None = None


@dataclass(frozen=True)
class FetchPlan:
    source_id: str
    action: str
    destination: str
    blocked_reason: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class SyntheaValidationReport:
    status: str
    external_schema_validation: str
    bundle_count: int
    resource_count: int
    patient_count: int
    declared_patient_count: int
    errors: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["errors"] = list(self.errors)
        return value


@dataclass(frozen=True)
class FetchResult:
    source_id: str
    output_dir: Path
    files: tuple[Path, ...]
    retrieved_at: str | None
    source_revision: str
    source_urls: tuple[str, ...] = ()
    validation: SyntheaValidationReport | None = None


@dataclass(frozen=True)
class FileRecord:
    source_id: str
    relative_path: str
    size_bytes: int
    sha256: str
    status: str
    source_url: str
    source_revision: str
    content_type: str | None = None
    validation_errors: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["validation_errors"] = list(self.validation_errors)
        return value


@dataclass(frozen=True)
class NormalizedDocument:
    document_id: str
    source_id: str
    source_url: str
    source_revision: str
    retrieved_at: str | None
    title: str
    publisher: str
    document_type: str
    effective_date: str | None
    language: str
    text: str | None
    raw_relative_path: str
    content_hash: str
    license_status: str
    redistribution_allowed: bool
    third_party_llm_allowed: bool
    normalization_status: str
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def source_specs() -> dict[str, SourceSpec]:
    """Return the reviewed, stable source configuration used by the CLI."""

    public_policy = FetchPolicy(
        rate_limit_seconds=1.0,
        retries=3,
        allowed_content_types=("text/html", "application/pdf", "application/xml", "text/xml"),
    )
    return {
        "synthea": SourceSpec(
            source_id="synthea",
            fetcher="synthea",
            url="https://github.com/synthetichealth/synthea",
            version=SYNTHEA_DEFAULT_VERSION,
            planned_scale="1,000 patients",
            output_subdir="synthea",
            license=LicenseGate("APPROVED", True, True, "docs/DATA_SOURCES.md:Synthea"),
            third_party_llm_note="Synthetic records only.",
        ),
        "mtsamples": SourceSpec(
            source_id="mtsamples",
            fetcher="mtsamples",
            url="https://www.kaggle.com/datasets/tboyle10/medicaltranscriptions",
            version="source-page-review-required",
            planned_scale="up to 4,999 notes",
            output_subdir="mtsamples",
            license=LicenseGate(
                "CONDITIONAL",
                True,
                False,
                "docs/DATA_SOURCES.md:MTSamples; verify source page before release",
            ),
            credential_env=("KAGGLE_API_TOKEN", "KAGGLE_USERNAME+KAGGLE_KEY"),
            third_party_llm_note="Enable only after local PHI scan and source-page approval.",
        ),
        "pmc-patients": SourceSpec(
            source_id="pmc-patients",
            fetcher="pmc-patients",
            url="https://huggingface.co/datasets/THUMedInfo/PMC-Patients",
            version="source-revision-required",
            planned_scale="up to 20,000 rows",
            output_subdir="pmc-patients",
            license=LicenseGate(
                "APPROVED_LOCAL_ONLY",
                False,
                False,
                "docs/DATA_SOURCES.md:PMC-Patients; CC-BY-NC-SA-4.0",
            ),
            third_party_llm_note="Local-only sample; no commercial-provider evaluation.",
        ),
        "cdc": SourceSpec(
            source_id="cdc",
            fetcher="guidance",
            url="https://www.cdc.gov/",
            version="allowlist-revision-required",
            planned_scale="selected guidance pages",
            output_subdir="cdc",
            license=LicenseGate("APPROVED", True, True, "docs/DATA_SOURCES.md:CDC"),
            policy=public_policy,
        ),
        "uspstf": SourceSpec(
            source_id="uspstf",
            fetcher="guidance",
            url="https://www.uspreventiveservicestaskforce.org/",
            version="allowlist-revision-required",
            planned_scale="selected recommendations",
            output_subdir="uspstf",
            license=LicenseGate("APPROVED", True, True, "docs/DATA_SOURCES.md:USPSTF"),
            policy=public_policy,
        ),
        "ahrq": SourceSpec(
            source_id="ahrq",
            fetcher="guidance",
            url="https://www.ahrq.gov/",
            version="allowlist-revision-required",
            planned_scale="selected guidance pages",
            output_subdir="ahrq",
            license=LicenseGate("APPROVED", True, True, "docs/DATA_SOURCES.md:AHRQ"),
            policy=public_policy,
        ),
        "dailymed": SourceSpec(
            source_id="dailymed",
            fetcher="guidance",
            url="https://dailymed.nlm.nih.gov/",
            version="label-review-required",
            planned_scale="selected labels",
            output_subdir="dailymed",
            license=LicenseGate(
                "CONDITIONAL",
                False,
                False,
                "docs/DATA_SOURCES.md:FDA DailyMed; label-specific review required",
            ),
            third_party_llm_note="Scripts are allowed; labels require individual review.",
            policy=FetchPolicy(
                rate_limit_seconds=1.0,
                retries=3,
                allowed_content_types=("text/html", "application/pdf", "application/xml", "text/xml"),
            ),
        ),
    }


def build_source_manifest(
    specs: Mapping[str, SourceSpec] | None = None,
    *,
    synthea: SyntheaConfig | None = None,
) -> dict[str, Any]:
    selected = specs or source_specs()
    generation = synthea or SyntheaConfig()
    return {
        "manifest_type": "source",
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "project": "MedAssist",
        "sources": [selected[key].as_dict() for key in sorted(selected)],
        "generators": {"synthea": generation.as_dict()},
        "reproducibility": {
            "source_order": "lexicographic by source_id",
            "network_policy": "robots.txt, per-host rate limit, bounded retries, atomic writes",
            "raw_data_policy": "raw files are local artifacts and must not be committed",
        },
    }


def build_file_manifest(records: Iterable[FileRecord] = ()) -> dict[str, Any]:
    ordered = sorted((record.as_dict() for record in records), key=lambda item: (item["source_id"], item["relative_path"]))
    return {
        "manifest_type": "file",
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "project": "MedAssist",
        "files": ordered,
    }


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def validate_raw_file(
    path: Path,
    *,
    expected_sha256: str | None = None,
    max_bytes: int = FetchPolicy.max_bytes,
    content_type: str | None = None,
    allowed_content_types: Sequence[str] = (),
) -> FileRecord:
    if not path.is_file():
        raise ValidationError(f"raw file does not exist: {path}")
    size = path.stat().st_size
    if size == 0:
        raise ValidationError(f"raw file is empty: {path}")
    if size > max_bytes:
        raise ValidationError(f"raw file exceeds max_bytes={max_bytes}: {path}")
    if content_type and allowed_content_types and content_type.split(";", 1)[0].strip() not in allowed_content_types:
        raise ValidationError(f"content type is not allowed for {path}: {content_type}")
    actual_sha256 = sha256_file(path)
    if expected_sha256 and actual_sha256.lower() != expected_sha256.lower():
        raise ValidationError(
            f"SHA256 mismatch for {path}: expected {expected_sha256.lower()}, got {actual_sha256}"
        )
    return FileRecord(
        source_id="",
        relative_path=path.name,
        size_bytes=size,
        sha256=actual_sha256,
        status="READY",
        source_url="",
        source_revision="",
        content_type=content_type,
    )


class RateLimiter:
    def __init__(
        self,
        interval_seconds: float,
        *,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.interval_seconds = max(0.0, interval_seconds)
        self.clock = clock
        self.sleeper = sleeper
        self._last_request: dict[str, float] = {}

    def wait(self, host: str) -> None:
        now = self.clock()
        last = self._last_request.get(host)
        if last is not None:
            remaining = self.interval_seconds - (now - last)
            if remaining > 0:
                self.sleeper(remaining)
                now = self.clock()
        self._last_request[host] = now


def robots_allowed(
    url: str,
    *,
    user_agent: str,
    robots_reader: Callable[[str], str] | None = None,
) -> bool:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return True
    robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
    parser = RobotFileParser()
    parser.set_url(robots_url)
    try:
        if robots_reader is None:
            parser.read()
        else:
            parser.parse(robots_reader(robots_url).splitlines())
    except (OSError, URLError, TimeoutError) as exc:
        raise FetchError(f"cannot verify robots.txt for {parsed.netloc}; refusing download") from exc
    return parser.can_fetch(user_agent, url)


def _retryable(error: BaseException) -> bool:
    if isinstance(error, HTTPError):
        return error.code == 429 or 500 <= error.code < 600
    return isinstance(error, (URLError, TimeoutError, ConnectionError))


def download_file(
    url: str,
    destination: Path,
    *,
    policy: FetchPolicy,
    expected_sha256: str | None = None,
    resume: bool = False,
    force: bool = False,
    opener: Callable[..., Any] = urlopen,
    robot_checker: Callable[[str, str], bool] | None = None,
    rate_limiter: RateLimiter | None = None,
    extra_headers: Mapping[str, str] | None = None,
) -> FileRecord:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise FetchError(f"unsupported download URL scheme: {url}")
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and not force:
        return validate_raw_file(
            destination,
            expected_sha256=expected_sha256,
            max_bytes=policy.max_bytes,
            allowed_content_types=policy.allowed_content_types,
        )
    user_agent = policy.user_agent
    allowed = robot_checker(url, user_agent) if robot_checker else robots_allowed(url, user_agent=user_agent)
    if not allowed:
        raise GateError(f"robots.txt disallows download: {url}")
    limiter = rate_limiter or RateLimiter(policy.rate_limit_seconds)
    part = destination.with_name(destination.name + ".part")
    start_at = part.stat().st_size if resume and part.exists() and not force else 0
    if force and part.exists():
        part.unlink()

    last_error: BaseException | None = None
    for attempt in range(policy.retries + 1):
        try:
            limiter.wait(parsed.netloc)
            headers = {"User-Agent": user_agent, **(extra_headers or {})}
            if start_at:
                headers["Range"] = f"bytes={start_at}-"
            request = Request(url, headers=headers)
            with opener(request, timeout=policy.timeout_seconds) as response:
                status = getattr(response, "status", None)
                append = bool(start_at and status == 206)
                if start_at and status not in {200, 206, None}:
                    raise FetchError(f"unexpected HTTP status for resume: {status}")
                mode = "ab" if append else "wb"
                total = start_at if append else 0
                content_type = response.headers.get("Content-Type") if response.headers else None
                with part.open(mode) as output:
                    while chunk := response.read(1024 * 1024):
                        total += len(chunk)
                        if total > policy.max_bytes:
                            raise ValidationError(f"download exceeds max_bytes={policy.max_bytes}: {url}")
                        output.write(chunk)
                start_at = total
            record = validate_raw_file(
                part,
                expected_sha256=expected_sha256,
                max_bytes=policy.max_bytes,
                content_type=content_type,
                allowed_content_types=policy.allowed_content_types,
            )
            part.replace(destination)
            return record
        except BaseException as exc:  # noqa: BLE001 - retry policy is intentionally centralized.
            last_error = exc
            if resume and part.exists():
                start_at = part.stat().st_size
            if not _retryable(exc) or attempt >= policy.retries:
                if isinstance(exc, FetchError):
                    raise
                raise FetchError(f"download failed after {attempt + 1} attempt(s): {url}: {exc}") from exc
            time.sleep(policy.backoff_seconds * (2**attempt))
    raise FetchError(f"download failed: {url}: {last_error}")


def _safe_child(root: Path, child: Path) -> Path:
    root_resolved = root.resolve()
    child_resolved = child.resolve()
    if child_resolved == root_resolved or root_resolved not in child_resolved.parents:
        raise ConfigurationError(f"refusing path outside output directory: {child_resolved}")
    return child_resolved


def _credentials_available(spec: SourceSpec, environ: Mapping[str, str]) -> bool:
    if not spec.credential_env:
        return True
    if "KAGGLE_API_TOKEN" in spec.credential_env and environ.get("KAGGLE_API_TOKEN"):
        return True
    return bool(environ.get("KAGGLE_USERNAME") and environ.get("KAGGLE_KEY"))


def enforce_source_gate(spec: SourceSpec, *, environ: Mapping[str, str] | None = None) -> None:
    env = environ or os.environ
    if spec.license.status not in {"APPROVED", "APPROVED_LOCAL_ONLY"}:
        raise GateError(
            f"source '{spec.source_id}' is not approved for fetching: "
            f"status={spec.license.status}; see {spec.license.approval_reference}"
        )
    if not _credentials_available(spec, env):
        required = " or ".join(spec.credential_env)
        raise ConfigurationError(f"source '{spec.source_id}' requires credentials: {required}")


def enforce_third_party_llm_gate(spec: SourceSpec) -> None:
    if not spec.license.third_party_llm_allowed:
        raise GateError(
            f"source '{spec.source_id}' is blocked from third-party LLM use; "
            f"see {spec.license.approval_reference}"
        )


class SourceFetcher(ABC):
    @abstractmethod
    def plan(self, spec: SourceSpec, context: FetchContext) -> FetchPlan:
        raise NotImplementedError

    @abstractmethod
    def fetch(self, spec: SourceSpec, context: FetchContext) -> FetchResult:
        raise NotImplementedError


def _environment(context: FetchContext) -> Mapping[str, str]:
    return context.environ if context.environ is not None else os.environ


def _env_value(environ: Mapping[str, str], *names: str) -> str | None:
    for name in names:
        value = environ.get(name)
        if value:
            return value
    return None


def _source_env_prefix(source_id: str) -> str:
    return "MEDASSIST_" + source_id.upper().replace("-", "_")


def _source_url(result: FetchResult, index: int, fallback: str) -> str:
    if index < len(result.source_urls):
        return result.source_urls[index]
    return fallback


def _read_json_file(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ConfigurationError(f"cannot read configuration file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ConfigurationError(f"configuration file is not valid JSON: {path}") from exc


def _allowlisted_urls(source_id: str, environ: Mapping[str, str]) -> tuple[str, ...]:
    prefix = _source_env_prefix(source_id)
    configured = _env_value(
        environ,
        f"{prefix}_ALLOWLIST",
        f"{prefix}_ALLOWLIST_FILE",
        "MEDASSIST_GUIDANCE_ALLOWLIST",
        "MEDASSIST_GUIDANCE_ALLOWLIST_FILE",
    )
    if not configured:
        raise ConfigurationError(
            f"source '{source_id}' requires an allowlist; set {prefix}_ALLOWLIST "
            "to a target URL or JSON file (site crawling is disabled)"
        )

    value: Any = configured
    candidate = Path(configured).expanduser()
    if candidate.is_file():
        value = _read_json_file(candidate)
    elif configured.lstrip().startswith(("[", "{")):
        try:
            value = json.loads(configured)
        except json.JSONDecodeError as exc:
            raise ConfigurationError(f"{prefix}_ALLOWLIST contains invalid JSON") from exc

    if isinstance(value, str):
        values: Any = [value]
    elif isinstance(value, list):
        values = value
    elif isinstance(value, dict):
        values = value.get(source_id, value.get("urls"))
    else:
        values = None
    if not isinstance(values, list) or not values:
        raise ConfigurationError(
            f"allowlist for '{source_id}' must contain a non-empty JSON 'urls' array"
        )

    urls: list[str] = []
    for value in values:
        if not isinstance(value, str) or urlparse(value).scheme not in {"http", "https"}:
            raise ConfigurationError(f"allowlist for '{source_id}' contains a non-http URL: {value!r}")
        if value not in urls:
            urls.append(value)
    return tuple(urls)


def _download_name(url: str, index: int) -> str:
    parsed = urlparse(url)
    raw_name = unquote(Path(parsed.path).name)
    suffix = Path(raw_name).suffix if raw_name and raw_name not in {".", ".."} else ""
    if len(suffix) > 16 or any(char in suffix for char in "/\\\0"):
        suffix = ""
    return f"{index:04d}-{hashlib.sha256(url.encode('utf-8')).hexdigest()[:12]}{suffix}"


class GuidanceFetcher(SourceFetcher):
    def plan(self, spec: SourceSpec, context: FetchContext) -> FetchPlan:
        reason = None
        try:
            urls = _allowlisted_urls(spec.source_id, _environment(context))
            action = f"download {len(urls)} allowlisted {spec.source_id} URL(s)"
        except ConfigurationError as exc:
            reason = str(exc)
            action = f"download allowlisted {spec.source_id} URLs"
        return FetchPlan(spec.source_id, action, str(context.output_dir / spec.output_subdir), reason)

    def fetch(self, spec: SourceSpec, context: FetchContext) -> FetchResult:
        enforce_source_gate(spec, environ=_environment(context))
        urls = _allowlisted_urls(spec.source_id, _environment(context))
        output_dir = _safe_child(context.output_dir, context.output_dir / spec.output_subdir)
        output_dir.mkdir(parents=True, exist_ok=True)
        files: list[Path] = []
        for index, url in enumerate(urls, start=1):
            destination = _safe_child(output_dir, output_dir / _download_name(url, index))
            _download(url, destination, spec=spec, context=context)
            files.append(destination)
        revision = "allowlist:" + hashlib.sha256("\n".join(urls).encode("utf-8")).hexdigest()[:16]
        return FetchResult(
            spec.source_id,
            output_dir,
            tuple(files),
            datetime.now(tz=UTC).isoformat(),
            revision,
            tuple(urls),
        )


def _download(
    url: str,
    destination: Path,
    *,
    spec: SourceSpec,
    context: FetchContext,
    extra_headers: Mapping[str, str] | None = None,
) -> FileRecord:
    return download_file(
        url,
        destination,
        policy=spec.policy,
        resume=context.resume,
        force=context.force,
        extra_headers=extra_headers,
    )


def _copy_local_file(source: Path, destination: Path, *, policy: FetchPolicy, force: bool) -> FileRecord:
    if not source.is_file():
        raise ConfigurationError(f"configured local data file does not exist: {source}")
    if destination.exists() and not force:
        return validate_raw_file(destination, max_bytes=policy.max_bytes)
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = destination.with_name(destination.name + ".part")
    total = 0
    try:
        with source.open("rb") as input_handle, part.open("wb") as output_handle:
            while chunk := input_handle.read(1024 * 1024):
                total += len(chunk)
                if total > policy.max_bytes:
                    raise ValidationError(f"local file exceeds max_bytes={policy.max_bytes}: {source}")
                output_handle.write(chunk)
        part.replace(destination)
    except (OSError, ValidationError):
        if part.exists():
            part.unlink()
        raise
    return validate_raw_file(destination, max_bytes=policy.max_bytes)


def _record_count(path: Path, suffix: str) -> int:
    if suffix in {".jsonl", ".ndjson", ".txt", ".md"}:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            return sum(1 for line in handle if line.strip())
    if suffix in {".csv", ".tsv"}:
        with path.open("r", encoding="utf-8", errors="replace", newline="") as handle:
            rows = csv.reader(handle, delimiter="\t" if suffix == ".tsv" else ",")
            next(rows, None)
            return sum(1 for row in rows if any(cell.strip() for cell in row))
    if suffix == ".json":
        value = _read_json_file(path)
        if isinstance(value, list):
            return len(value)
        if isinstance(value, dict):
            for key in ("rows", "records", "data"):
                if isinstance(value.get(key), list):
                    return len(value[key])
        return 1
    raise ValidationError(f"PMC-Patients file format is unsupported for record limiting: {path.name}")


def _limit_records(path: Path, limit: int) -> int:
    suffix = path.suffix.lower()
    count = _record_count(path, suffix)
    if count <= limit:
        return count
    part = path.with_name(path.name + ".sample.part")
    try:
        if suffix in {".jsonl", ".ndjson", ".txt", ".md"}:
            kept = 0
            with path.open("r", encoding="utf-8", errors="replace") as input_handle, part.open("w", encoding="utf-8") as output_handle:
                for line in input_handle:
                    if line.strip():
                        if kept >= limit:
                            break
                        kept += 1
                    output_handle.write(line)
        elif suffix in {".csv", ".tsv"}:
            with path.open("r", encoding="utf-8", errors="replace", newline="") as input_handle, part.open("w", encoding="utf-8", newline="") as output_handle:
                rows = csv.reader(input_handle, delimiter="\t" if suffix == ".tsv" else ",")
                writer = csv.writer(output_handle, delimiter="\t" if suffix == ".tsv" else ",")
                header = next(rows, None)
                if header is not None:
                    writer.writerow(header)
                for index, row in enumerate(rows):
                    if index >= limit:
                        break
                    writer.writerow(row)
        elif suffix == ".json":
            value = _read_json_file(path)
            if isinstance(value, list):
                value = value[:limit]
            elif isinstance(value, dict):
                for key in ("rows", "records", "data"):
                    if isinstance(value.get(key), list):
                        value[key] = value[key][:limit]
                        break
            part.write_text(json.dumps(value, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        else:
            raise ValidationError(f"PMC-Patients file format is unsupported for record limiting: {path.name}")
        part.replace(path)
    except OSError:
        if part.exists():
            part.unlink()
        raise
    return limit


class PMCPatientsFetcher(SourceFetcher):
    def _configuration(self, context: FetchContext) -> tuple[Path | None, str | None, int]:
        env = _environment(context)
        prefix = _source_env_prefix("pmc-patients")
        local_value = _env_value(env, f"{prefix}_LOCAL_FILE", "MEDASSIST_PMC_LOCAL_FILE")
        url = _env_value(env, f"{prefix}_DATASET_URL", f"{prefix}_URL")
        revision = _env_value(env, f"{prefix}_REVISION")
        raw_sample = _env_value(env, f"{prefix}_SAMPLE", f"{prefix}_SAMPLE_LIMIT")
        if not revision:
            raise ConfigurationError(f"source 'pmc-patients' requires {prefix}_REVISION")
        try:
            sample = int(raw_sample or "20000")
        except ValueError as exc:
            raise ConfigurationError(f"{prefix}_SAMPLE must be an integer") from exc
        if not 1 <= sample <= 20000:
            raise ConfigurationError(f"{prefix}_SAMPLE must be between 1 and 20000")
        if not local_value and not url:
            raise ConfigurationError(
                "source 'pmc-patients' requires a local file or explicit dataset file URL; "
                "set MEDASSIST_PMC_PATIENTS_LOCAL_FILE or MEDASSIST_PMC_PATIENTS_DATASET_URL"
            )
        local = Path(local_value).expanduser() if local_value else None
        if url and urlparse(url).scheme not in {"http", "https"}:
            raise ConfigurationError(f"{prefix}_DATASET_URL must be an http(s) URL")
        return local, url, sample

    def plan(self, spec: SourceSpec, context: FetchContext) -> FetchPlan:
        try:
            local, url, sample = self._configuration(context)
            source = f"local file {local}" if local else f"explicit URL {url}"
            reason = None
            action = f"use {source}, limited to {sample} PMC-Patients records"
        except ConfigurationError as exc:
            reason = str(exc)
            action = "use a bounded PMC-Patients local file or explicit dataset file URL"
        return FetchPlan(spec.source_id, action, str(context.output_dir / spec.output_subdir), reason)

    def fetch(self, spec: SourceSpec, context: FetchContext) -> FetchResult:
        enforce_source_gate(spec, environ=_environment(context))
        local, url, sample = self._configuration(context)
        output_dir = _safe_child(context.output_dir, context.output_dir / spec.output_subdir)
        output_dir.mkdir(parents=True, exist_ok=True)
        configured_name = local.name if local else Path(urlparse(url or "").path).name
        suffix = Path(configured_name).suffix.lower()
        if suffix not in {".json", ".jsonl", ".ndjson", ".csv", ".tsv", ".txt", ".md"}:
            suffix = ".jsonl"
        destination = _safe_child(output_dir, output_dir / f"pmc-patients{suffix}")
        if local:
            _copy_local_file(local, destination, policy=spec.policy, force=context.force)
        else:
            assert url is not None
            _download(url, destination, spec=spec, context=context)
        _limit_records(destination, sample)
        validate_raw_file(destination, max_bytes=spec.policy.max_bytes)
        source_url = url if url else local.resolve().as_uri()
        return FetchResult(
            spec.source_id,
            output_dir,
            (destination,),
            datetime.now(tz=UTC).isoformat(),
            _environment(context)[_source_env_prefix("pmc-patients") + "_REVISION"],
            (source_url,),
        )


def _kaggle_slug(url: str) -> str:
    parts = [part for part in urlparse(url).path.split("/") if part]
    if len(parts) >= 3 and parts[0] == "datasets":
        return "/".join(parts[1:3])
    return "tboyle10/medicaltranscriptions"


def _kaggle_headers(environ: Mapping[str, str]) -> dict[str, str]:
    token = environ.get("KAGGLE_API_TOKEN")
    if token:
        return {"Authorization": f"Bearer {token}"}
    username, key = environ.get("KAGGLE_USERNAME"), environ.get("KAGGLE_KEY")
    if username and key:
        encoded = base64.b64encode(f"{username}:{key}".encode("utf-8")).decode("ascii")
        return {"Authorization": f"Basic {encoded}"}
    return {}


def _extract_zip(archive: Path, output_dir: Path, *, max_bytes: int) -> tuple[Path, ...]:
    files: list[Path] = []
    total = 0
    try:
        with zipfile.ZipFile(archive) as handle:
            for member in handle.infolist():
                if member.is_dir():
                    continue
                member_path = Path(member.filename)
                if member_path.is_absolute() or ".." in member_path.parts:
                    raise ConfigurationError(f"archive contains unsafe path: {member.filename}")
                total += member.file_size
                if total > max_bytes:
                    raise ValidationError(f"archive contents exceed max_bytes={max_bytes}: {archive}")
                destination = _safe_child(output_dir, output_dir / member_path)
                destination.parent.mkdir(parents=True, exist_ok=True)
                with handle.open(member) as input_handle, destination.open("wb") as output_handle:
                    shutil.copyfileobj(input_handle, output_handle, length=1024 * 1024)
                validate_raw_file(destination, max_bytes=max_bytes)
                files.append(destination)
    except zipfile.BadZipFile as exc:
        raise ValidationError(f"downloaded archive is not a valid ZIP: {archive}") from exc
    return tuple(sorted(files))


class MTSamplesFetcher(SourceFetcher):
    def _configuration(self, context: FetchContext) -> tuple[str, bool]:
        env = _environment(context)
        prefix = _source_env_prefix("mtsamples")
        explicit_url = _env_value(env, f"{prefix}_FILE_URL", f"{prefix}_URL")
        if explicit_url:
            if urlparse(explicit_url).scheme not in {"http", "https"}:
                raise ConfigurationError(f"{prefix}_FILE_URL must be an http(s) URL")
            return explicit_url, False
        slug = _env_value(env, f"{prefix}_KAGGLE_DATASET") or _kaggle_slug("https://www.kaggle.com/datasets/tboyle10/medicaltranscriptions")
        if "/" not in slug:
            raise ConfigurationError(f"{prefix}_KAGGLE_DATASET must be owner/dataset")
        return f"https://www.kaggle.com/api/v1/datasets/download/{slug}", True

    def plan(self, spec: SourceSpec, context: FetchContext) -> FetchPlan:
        try:
            url, is_kaggle = self._configuration(context)
            reason = None
            action = f"download {'Kaggle dataset' if is_kaggle else 'explicit file'} {url}"
        except ConfigurationError as exc:
            url, reason, action = spec.url, str(exc), "download MTSamples from Kaggle API or an explicit file URL"
        try:
            enforce_source_gate(spec, environ=_environment(context))
        except FetchError as exc:
            reason = reason or str(exc)
        return FetchPlan(spec.source_id, action, str(context.output_dir / spec.output_subdir), reason)

    def fetch(self, spec: SourceSpec, context: FetchContext) -> FetchResult:
        enforce_source_gate(spec, environ=_environment(context))
        url, is_kaggle = self._configuration(context)
        output_dir = _safe_child(context.output_dir, context.output_dir / spec.output_subdir)
        output_dir.mkdir(parents=True, exist_ok=True)
        archive = _safe_child(output_dir, output_dir / "mtsamples.download")
        _download(url, archive, spec=spec, context=context, extra_headers=_kaggle_headers(_environment(context)) if is_kaggle else None)
        if zipfile.is_zipfile(archive):
            files = _extract_zip(archive, output_dir, max_bytes=spec.policy.max_bytes)
            if not context.resume:
                archive.unlink()
        else:
            raw_name = unquote(Path(urlparse(url).path).name)
            suffix = Path(raw_name).suffix if raw_name else ""
            if len(suffix) > 16 or any(char in suffix for char in "/\\\0"):
                suffix = ""
            destination = _safe_child(output_dir, output_dir / f"mtsamples{suffix or '.data'}")
            archive.replace(destination)
            files = (destination,)
        if not files:
            raise ValidationError("MTSamples archive contained no files")
        revision = "kaggle:" + _kaggle_slug(url) if is_kaggle else "file:" + hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
        return FetchResult(
            spec.source_id,
            output_dir,
            files,
            datetime.now(tz=UTC).isoformat(),
            revision,
            (url,) * len(files),
        )


FHIR_BUNDLE_TYPES = {
    "document",
    "message",
    "transaction",
    "transaction-response",
    "batch",
    "batch-response",
    "history",
    "searchset",
    "collection",
}


def validate_synthea_output(
    output_dir: Path,
    files: Sequence[Path],
    *,
    patient_count: int,
) -> SyntheaValidationReport:
    """Validate the local invariants we can prove without downloading a validator.

    Synthea's FHIR output normally contains one transaction Bundle per patient.
    The validator intentionally counts Patient resources rather than assuming a
    particular file layout, while still rejecting malformed bundles and duplicate
    patient identities. Full FHIR R4 schema validation remains explicitly marked
    as unverified because no external validator or binary is fetched here.
    """

    if patient_count < 1:
        raise ValidationError(f"Synthea patient_count must be positive, got {patient_count}")
    if not output_dir.is_dir():
        raise ValidationError(f"Synthea output directory does not exist: {output_dir}")
    if not files:
        raise ValidationError(f"Synthea output contains no files: {output_dir}")

    safe_files: list[Path] = []
    errors: list[str] = []
    for path in files:
        try:
            safe_path = _safe_child(output_dir, path if path.is_absolute() else output_dir / path)
        except ConfigurationError as exc:
            errors.append(str(exc))
            continue
        if not safe_path.is_file():
            errors.append(f"Synthea output file does not exist: {safe_path}")
            continue
        if safe_path.stat().st_size == 0:
            errors.append(f"Synthea output file is empty: {safe_path}")
            continue
        safe_files.append(safe_path)

    json_files = [path for path in safe_files if path.suffix.lower() == ".json"]
    if not json_files:
        errors.append("Synthea output contains no JSON FHIR Bundle files")

    bundle_count = 0
    resource_count = 0
    patient_resource_count = 0
    patient_ids: set[str] = set()
    for path in json_files:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            errors.append(f"{path}: invalid JSON ({exc})")
            continue
        if not isinstance(value, dict) or value.get("resourceType") != "Bundle":
            errors.append(f"{path}: expected a FHIR Bundle resource")
            continue
        bundle_type = value.get("type")
        if bundle_type not in FHIR_BUNDLE_TYPES:
            errors.append(f"{path}: invalid or missing FHIR Bundle.type: {bundle_type!r}")
        fhir_version = value.get("fhirVersion")
        if fhir_version is not None and not str(fhir_version).startswith("4.0"):
            errors.append(f"{path}: unsupported FHIR version marker: {fhir_version!r}")
        entries = value.get("entry")
        if not isinstance(entries, list):
            errors.append(f"{path}: FHIR Bundle.entry must be an array")
            continue

        bundle_count += 1
        for entry_index, entry in enumerate(entries, start=1):
            if not isinstance(entry, dict) or not isinstance(entry.get("resource"), dict):
                errors.append(f"{path}: entry {entry_index} has no resource object")
                continue
            resource = entry["resource"]
            resource_type = resource.get("resourceType")
            if not isinstance(resource_type, str) or not resource_type:
                errors.append(f"{path}: entry {entry_index} resourceType is missing")
                continue
            resource_count += 1
            if resource_type != "Patient":
                continue
            patient_resource_count += 1
            patient_id = resource.get("id")
            if not isinstance(patient_id, str) or not patient_id.strip():
                errors.append(f"{path}: entry {entry_index} Patient.id is missing")
                continue
            if patient_id in patient_ids:
                errors.append(f"{path}: duplicate Patient.id {patient_id!r}")
            patient_ids.add(patient_id)

    if patient_resource_count != patient_count:
        errors.append(
            "Synthea patient population mismatch: "
            f"declared={patient_count}, observed={patient_resource_count}"
        )
    if errors:
        raise ValidationError("Synthea output validation failed: " + "; ".join(errors))

    return SyntheaValidationReport(
        status="LOCAL_INVARIANTS_PASSED",
        external_schema_validation="UNVERIFIED",
        bundle_count=bundle_count,
        resource_count=resource_count,
        patient_count=patient_resource_count,
        declared_patient_count=patient_count,
    )


class SyntheaFetcher(SourceFetcher):
    def plan(self, spec: SourceSpec, context: FetchContext) -> FetchPlan:
        command = context.synthea.command_template or os.environ.get("MEDASSIST_SYNTHEA_COMMAND")
        reason = None if command else "MEDASSIST_SYNTHEA_COMMAND or --synthea-command is required"
        return FetchPlan(
            source_id=spec.source_id,
            action=(
                f"run Synthea v{context.synthea.version} with "
                f"patients={context.synthea.patient_count}, seed={context.synthea.seed}"
            ),
            destination=str(context.output_dir / spec.output_subdir),
            blocked_reason=reason,
        )

    def fetch(self, spec: SourceSpec, context: FetchContext) -> FetchResult:
        enforce_source_gate(spec)
        output_dir = _safe_child(context.output_dir, context.output_dir / spec.output_subdir)
        output_dir.mkdir(parents=True, exist_ok=True)
        existing_files = tuple(sorted(path for path in output_dir.rglob("*") if path.is_file()))
        if context.resume and existing_files and not context.force:
            validation = validate_synthea_output(
                output_dir,
                existing_files,
                patient_count=context.synthea.patient_count,
            )
            return FetchResult(
                spec.source_id,
                output_dir,
                existing_files,
                None,
                context.synthea.version,
                (spec.url,) * len(existing_files),
                validation,
            )
        command_template = context.synthea.command_template or os.environ.get("MEDASSIST_SYNTHEA_COMMAND")
        if not command_template:
            raise ConfigurationError(
                "Synthea command is not configured; set MEDASSIST_SYNTHEA_COMMAND "
                "or pass --synthea-command"
            )
        if context.force and existing_files:
            for path in existing_files:
                path.unlink()
        try:
            command = command_template.format(
                output_dir=str(output_dir),
                patients=context.synthea.patient_count,
                seed=context.synthea.seed,
                version=context.synthea.version,
            )
        except KeyError as exc:
            raise ConfigurationError(f"unknown Synthea command placeholder: {exc}") from exc
        argv = shlex.split(command, posix=os.name != "nt")
        if not argv:
            raise ConfigurationError("Synthea command is empty")
        subprocess.run(argv, cwd=ROOT, check=True)
        files = tuple(sorted(path for path in output_dir.rglob("*") if path.is_file()))
        if not files:
            raise ValidationError(f"Synthea command produced no files in {output_dir}")
        validation = validate_synthea_output(
            output_dir,
            files,
            patient_count=context.synthea.patient_count,
        )
        retrieved_at = datetime.now(tz=UTC).isoformat()
        return FetchResult(
            spec.source_id,
            output_dir,
            files,
            retrieved_at,
            context.synthea.version,
            (spec.url,) * len(files),
            validation,
        )


FETCHERS: dict[str, SourceFetcher] = {
    "synthea": SyntheaFetcher(),
    "mtsamples": MTSamplesFetcher(),
    "pmc-patients": PMCPatientsFetcher(),
    "cdc": GuidanceFetcher(),
    "uspstf": GuidanceFetcher(),
    "ahrq": GuidanceFetcher(),
    "dailymed": GuidanceFetcher(),
}


TEXT_EXTENSIONS = {".csv", ".htm", ".html", ".json", ".jsonl", ".md", ".txt", ".xml"}
STRUCTURED_RECORD_SOURCES = {"mtsamples", "pmc-patients"}
TEXT_FIELD_ALIASES = {
    "body",
    "content",
    "document",
    "medical_transcription",
    "note",
    "patient",
    "patient_summary",
    "summary",
    "text",
    "transcription",
}


@dataclass(frozen=True)
class _StructuredRecord:
    record_index: int
    identifier: str
    title: str
    text: str
    effective_date: str | None
    language: str
    metadata: dict[str, Any]


def _field_key(value: object) -> str:
    return re.sub(r"[^0-9a-z]+", "_", str(value).strip().lower()).strip("_")


def _json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, list):
        return [_json_safe(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    return str(value)


def _record_fields(record: Mapping[Any, Any], *, path: Path, location: str) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    for raw_key, value in record.items():
        key = _field_key(raw_key)
        if not key:
            raise ValidationError(f"{path}: {location} contains an empty field name")
        if key in fields:
            raise ValidationError(f"{path}: {location} contains duplicate field name {key!r}")
        fields[key] = value
    if not fields:
        raise ValidationError(f"{path}: {location} is an empty record")
    return fields


def _record_value(fields: Mapping[str, Any], *aliases: str) -> Any:
    for alias in aliases:
        value = fields.get(_field_key(alias))
        if value is not None:
            return value
    return None


def _value_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, (dict, list)):
        return _canonical_json(value)
    return str(value).strip()


def _first_text_value(fields: Mapping[str, Any], aliases: Sequence[str]) -> str:
    for alias in aliases:
        text = _value_text(_record_value(fields, alias))
        if text:
            return text
    return ""


def _normalise_structured_record(
    source_id: str,
    path: Path,
    record: Mapping[Any, Any],
    *,
    record_index: int,
    location: str,
) -> _StructuredRecord:
    fields = _record_fields(record, path=path, location=location)
    if source_id == "mtsamples":
        text_aliases = (
            "transcription",
            "medical_transcription",
            "text",
            "note",
            "content",
            "document",
            "body",
        )
    else:
        text_aliases = (
            "patient_summary",
            "patient",
            "summary",
            "text",
            "note",
            "content",
            "transcription",
            "document",
            "body",
            "description",
        )
    text = _first_text_value(fields, text_aliases)
    if not text:
        raise ValidationError(
            f"{path}: {location} has no usable narrative field; expected one of {', '.join(text_aliases)}"
        )

    identifier_value = _record_value(
        fields,
        "record_id",
        "note_id",
        "patient_uid",
        "uid",
        "id",
        "pmid",
        "pubmed_id",
        "unnamed_0",
    )
    identifier = _value_text(identifier_value) or f"record-{record_index:06d}"
    title = _first_text_value(fields, ("title", "sample_name", "description", "name"))
    if not title:
        title = f"{path.stem} record {identifier}"
    effective_date = _first_text_value(fields, ("effective_date", "date", "published_date")) or None
    language = _first_text_value(fields, ("language", "lang")) or "en"

    metadata: dict[str, Any] = {
        "record_index": record_index,
        "record_identifier": identifier,
        "record_location": location,
    }
    if location.startswith("row "):
        metadata["row_number"] = int(location.removeprefix("row "))
    elif location.startswith("line "):
        metadata["line_number"] = int(location.removeprefix("line "))
    else:
        metadata["record_number"] = record_index

    for raw_key, value in record.items():
        if _field_key(raw_key) not in TEXT_FIELD_ALIASES and value is not None:
            metadata[str(raw_key)] = _json_safe(value)
    for canonical, aliases in {
        "title": ("title", "sample_name", "description", "name"),
        "specialty": ("medical_specialty", "specialty", "department", "category"),
        "pmid": ("pmid", "pubmed_id"),
        "uid": ("patient_uid", "uid", "patient_id"),
    }.items():
        value = _record_value(fields, *aliases)
        if value is not None and _value_text(value):
            metadata[canonical] = _json_safe(value)

    return _StructuredRecord(
        record_index=record_index,
        identifier=identifier,
        title=title,
        text=text,
        effective_date=effective_date,
        language=language,
        metadata=metadata,
    )


def _structured_records_from_csv(source_id: str, path: Path) -> tuple[_StructuredRecord, ...]:
    records: list[_StructuredRecord] = []
    try:
        with path.open("r", encoding="utf-8", errors="strict", newline="") as handle:
            reader = csv.DictReader(
                handle,
                delimiter="\t" if path.suffix.lower() == ".tsv" else ",",
                strict=True,
            )
            if not reader.fieldnames or any(not _field_key(name) for name in reader.fieldnames):
                raise ValidationError(f"{path}: structured CSV requires a non-empty header")
            normalised_headers = [_field_key(name) for name in reader.fieldnames]
            if len(normalised_headers) != len(set(normalised_headers)):
                raise ValidationError(f"{path}: structured CSV contains duplicate header names")
            for record_index, row in enumerate(reader, start=1):
                location = f"row {reader.line_num}"
                if row is None or None in row or any(value is None for value in row.values()):
                    raise ValidationError(f"{path}: {location} has an inconsistent column count")
                if not any(_value_text(value) for value in row.values()):
                    continue
                records.append(
                    _normalise_structured_record(
                        source_id,
                        path,
                        row,
                        record_index=record_index,
                        location=location,
                    )
                )
    except csv.Error as exc:
        raise ValidationError(f"{path}: malformed CSV ({exc})") from exc
    except UnicodeError as exc:
        raise ValidationError(f"{path}: invalid text encoding ({exc})") from exc
    if not records:
        raise ValidationError(f"{path}: structured CSV contains no usable records")
    return tuple(records)


def _structured_records_from_json(source_id: str, path: Path) -> tuple[_StructuredRecord, ...]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValidationError(f"{path}: invalid JSON ({exc})") from exc

    if isinstance(value, list):
        raw_records = value
    elif isinstance(value, dict):
        raw_records = None
        for container_key in ("records", "rows", "items", "patients", "data"):
            candidate = value.get(container_key)
            if isinstance(candidate, list):
                raw_records = candidate
                break
        if raw_records is None:
            raw_records = [value]
    else:
        raise ValidationError(f"{path}: structured JSON must contain an object or array of records")

    if not raw_records:
        raise ValidationError(f"{path}: structured JSON contains no records")
    records: list[_StructuredRecord] = []
    for record_index, record in enumerate(raw_records, start=1):
        location = f"record {record_index}"
        if not isinstance(record, Mapping):
            raise ValidationError(f"{path}: {location} is not a JSON object")
        records.append(
            _normalise_structured_record(
                source_id,
                path,
                record,
                record_index=record_index,
                location=location,
            )
        )
    return tuple(records)


def _structured_records(source_id: str, path: Path) -> tuple[_StructuredRecord, ...] | None:
    if source_id not in STRUCTURED_RECORD_SOURCES:
        return None
    suffix = path.suffix.lower()
    if suffix in {".csv", ".tsv"}:
        return _structured_records_from_csv(source_id, path)
    if suffix in {".json", ".jsonl", ".ndjson"}:
        if suffix in {".jsonl", ".ndjson"}:
            records: list[_StructuredRecord] = []
            try:
                with path.open("r", encoding="utf-8", errors="strict") as handle:
                    for line_number, line in enumerate(handle, start=1):
                        if not line.strip():
                            continue
                        try:
                            value = json.loads(line)
                        except json.JSONDecodeError as exc:
                            raise ValidationError(f"{path}: line {line_number} is invalid JSON ({exc})") from exc
                        if not isinstance(value, Mapping):
                            raise ValidationError(f"{path}: line {line_number} is not a JSON object")
                        records.append(
                            _normalise_structured_record(
                                source_id,
                                path,
                                value,
                                record_index=len(records) + 1,
                                location=f"line {line_number}",
                            )
                        )
            except UnicodeError as exc:
                raise ValidationError(f"{path}: invalid text encoding ({exc})") from exc
            if not records:
                raise ValidationError(f"{path}: JSONL contains no records")
            return tuple(records)
        return _structured_records_from_json(source_id, path)
    return None


def _normalised_document_sort_key(document: NormalizedDocument) -> tuple[str, int, int, str]:
    record_index = document.metadata.get("record_index")
    if isinstance(record_index, int):
        return (document.raw_relative_path, 0, record_index, document.document_id)
    return (document.raw_relative_path, 1, 0, document.document_id)


def _document_id(source_id: str, relative_path: str) -> str:
    return hashlib.sha256(f"{source_id}:{relative_path}".encode("utf-8")).hexdigest()[:32]


def normalize_files(
    spec: SourceSpec,
    result: FetchResult,
    *,
    output_path: Path,
    publisher: str | None = None,
) -> tuple[NormalizedDocument, ...]:
    documents: list[NormalizedDocument] = []
    for index, path in enumerate(result.files):
        relative = path.relative_to(result.output_dir).as_posix()
        raw_file_hash = sha256_file(path)
        records = _structured_records(spec.source_id, path)
        if records is not None:
            for record in records:
                record_metadata = {
                    **record.metadata,
                    "raw_file_sha256": raw_file_hash,
                }
                documents.append(
                    NormalizedDocument(
                        document_id=_document_id(
                            spec.source_id,
                            f"{relative}#record={record.identifier}#index={record.record_index}",
                        ),
                        source_id=spec.source_id,
                        source_url=_source_url(result, index, spec.url),
                        source_revision=result.source_revision,
                        retrieved_at=result.retrieved_at,
                        title=record.title,
                        publisher=publisher or spec.source_id,
                        document_type=path.suffix.lower().lstrip(".") or "unknown",
                        effective_date=record.effective_date,
                        language=record.language,
                        text=record.text,
                        raw_relative_path=(Path(spec.output_subdir) / relative).as_posix(),
                        content_hash=hashlib.sha256(record.text.encode("utf-8")).hexdigest(),
                        license_status=spec.license.status,
                        redistribution_allowed=spec.license.redistribution_allowed,
                        third_party_llm_allowed=spec.license.third_party_llm_allowed,
                        normalization_status="NORMALIZED_RECORD",
                        metadata=record_metadata,
                    )
                )
            continue

        text: str | None
        normalization_status: str
        if path.suffix.lower() in TEXT_EXTENSIONS:
            text = path.read_text(encoding="utf-8", errors="replace")
            normalization_status = "NORMALIZED_TEXT"
        else:
            text = None
            normalization_status = "RAW_ONLY_UNSUPPORTED_TEXT_FORMAT"
        documents.append(
            NormalizedDocument(
                document_id=_document_id(spec.source_id, relative),
                source_id=spec.source_id,
                source_url=_source_url(result, index, spec.url),
                source_revision=result.source_revision,
                retrieved_at=result.retrieved_at,
                title=path.stem,
                publisher=publisher or spec.source_id,
                document_type=path.suffix.lower().lstrip(".") or "unknown",
                effective_date=None,
                language="en",
                text=text,
                raw_relative_path=(Path(spec.output_subdir) / relative).as_posix(),
                content_hash=raw_file_hash,
                license_status=spec.license.status,
                redistribution_allowed=spec.license.redistribution_allowed,
                third_party_llm_allowed=spec.license.third_party_llm_allowed,
                normalization_status=normalization_status,
                metadata={"raw_file_sha256": raw_file_hash},
            )
        )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    part_path = output_path.with_name(output_path.name + ".part")
    try:
        with part_path.open("w", encoding="utf-8", newline="\n") as handle:
            for document in sorted(
                documents,
                key=_normalised_document_sort_key,
            ):
                handle.write(_canonical_json(document.as_dict()) + "\n")
        part_path.replace(output_path)
    except OSError:
        if part_path.exists():
            part_path.unlink()
        raise
    return tuple(documents)


def collect_file_records(spec: SourceSpec, result: FetchResult) -> tuple[FileRecord, ...]:
    records: list[FileRecord] = []
    for index, path in enumerate(result.files):
        validated = validate_raw_file(path, max_bytes=spec.policy.max_bytes)
        relative = path.relative_to(result.output_dir).as_posix()
        records.append(
            FileRecord(
                source_id=spec.source_id,
                relative_path=(Path(spec.output_subdir) / relative).as_posix(),
                size_bytes=validated.size_bytes,
                sha256=validated.sha256,
                status=validated.status,
                source_url=_source_url(result, index, spec.url),
                source_revision=result.source_revision,
                content_type=mimetypes.guess_type(path.name)[0],
                validation_errors=validated.validation_errors,
            )
        )
    return tuple(records)


def _parse_sources(values: Sequence[str] | None, available: Mapping[str, SourceSpec]) -> list[str]:
    if not values:
        return sorted(available)
    names: list[str] = []
    for value in values:
        names.extend(part.strip() for part in value.split(",") if part.strip())
    unknown = sorted(set(names) - set(available))
    if unknown:
        raise ConfigurationError(f"unknown source(s): {', '.join(unknown)}")
    return list(dict.fromkeys(names))


def _build_parser() -> argparse.ArgumentParser:
    available = ", ".join(sorted(source_specs()))
    parser = argparse.ArgumentParser(description="Fetch and normalize reviewed MedAssist data sources.")
    parser.add_argument("--source", action="append", help=f"Source id; repeat or use commas. Available: {available}")
    parser.add_argument("--manifest-only", action="store_true", help="Write deterministic source and empty file manifests only.")
    parser.add_argument("--dry-run", action="store_true", help="Print the plan without network access or filesystem writes.")
    parser.add_argument("--resume", action="store_true", help="Reuse existing partial files and Synthea output where possible.")
    parser.add_argument("--force", action="store_true", help="Replace existing destination files/output when a fetch is run.")
    parser.add_argument("--output-dir", type=Path, default=DATA_DIR, help="Local data artifact directory.")
    parser.add_argument("--source-manifest", type=Path, help="Override source manifest output path.")
    parser.add_argument("--file-manifest", type=Path, help="Override file manifest output path.")
    parser.add_argument("--synthea-command", help="Command template with {output_dir}, {patients}, {seed}, and {version} placeholders.")
    parser.add_argument("--synthea-version", default=SYNTHEA_DEFAULT_VERSION)
    parser.add_argument("--synthea-seed", type=int, default=SYNTHEA_DEFAULT_SEED)
    parser.add_argument("--synthea-patients", type=int, default=SYNTHEA_PATIENT_COUNT)
    return parser


def _manifest_paths(args: argparse.Namespace) -> tuple[Path, Path]:
    output_dir = args.output_dir
    source_manifest = args.source_manifest or output_dir / "source-manifest.json"
    file_manifest = args.file_manifest or output_dir / "file-manifest.json"
    return source_manifest, file_manifest


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    specs = source_specs()
    try:
        selected_ids = _parse_sources(args.source, specs)
        selected_specs = {source_id: specs[source_id] for source_id in selected_ids}
        synthea_config = SyntheaConfig(
            patient_count=args.synthea_patients,
            seed=args.synthea_seed,
            version=args.synthea_version,
            command_template=args.synthea_command,
        )
        context = FetchContext(
            output_dir=args.output_dir,
            dry_run=args.dry_run,
            resume=args.resume,
            force=args.force,
            synthea=synthea_config,
        )
        source_manifest = build_source_manifest(selected_specs, synthea=synthea_config)
        source_manifest_path, file_manifest_path = _manifest_paths(args)
        plans = [FETCHERS[source_id].plan(selected_specs[source_id], context) for source_id in selected_ids]
        if args.dry_run:
            print(_canonical_json({"source_manifest": source_manifest, "plans": [plan.as_dict() for plan in plans]}))
            return 0
        source_manifest_path.parent.mkdir(parents=True, exist_ok=True)
        _write_json(source_manifest_path, source_manifest)
        if args.manifest_only:
            _write_json(file_manifest_path, build_file_manifest())
            print(f"Wrote source manifest: {source_manifest_path}")
            print(f"Wrote empty file manifest: {file_manifest_path}")
            return 0

        args.output_dir.mkdir(parents=True, exist_ok=True)
        records: list[FileRecord] = []
        for source_id in selected_ids:
            spec = selected_specs[source_id]
            result = FETCHERS[source_id].fetch(spec, context)
            records.extend(collect_file_records(spec, result))
            normalized_path = args.output_dir / "normalized" / f"{source_id}.jsonl"
            normalize_files(spec, result, output_path=normalized_path)
            if result.validation is not None:
                print(
                    "Synthea validation: "
                    f"{result.validation.status}; "
                    f"bundles={result.validation.bundle_count}; "
                    f"resources={result.validation.resource_count}; "
                    f"patients={result.validation.patient_count}; "
                    f"external_schema={result.validation.external_schema_validation}"
                )
        _write_json(file_manifest_path, build_file_manifest(records))
        print(f"Wrote source manifest: {source_manifest_path}")
        print(f"Wrote file manifest: {file_manifest_path}")
        return 0
    except FetchError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except subprocess.CalledProcessError as exc:
        print(f"ERROR: Synthea command failed with exit code {exc.returncode}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
