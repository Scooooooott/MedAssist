from __future__ import annotations

import hashlib
import hmac
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Protocol


SAFE_HARBOR_SURROGATE = "SAFE_HARBOR_SURROGATE"
SAFE_HARBOR_REDACT = "SAFE_HARBOR_REDACT"
DEFAULT_POLICY = SAFE_HARBOR_SURROGATE

# HHS Safe Harbor identifies these three-digit prefixes as having populations
# below 20,000. They must be emitted as 000 rather than retained.
LOW_POPULATION_ZIP3 = frozenset(
    {
        "036",
        "059",
        "063",
        "102",
        "203",
        "556",
        "692",
        "821",
        "823",
        "878",
        "879",
        "884",
    }
)


class DeidError(RuntimeError):
    """Base error for errors that must never fall back to plaintext."""


class DeidInitializationError(DeidError):
    """Raised when the production analyzer cannot be initialized or warmed up."""


class DeidUnavailableError(DeidError):
    """Raised by the fail-closed backend while the analyzer is unavailable."""


@dataclass(frozen=True, slots=True)
class PhiEntity:
    entity_type: str
    start: int
    end: int
    score: float
    recognizer: str


@dataclass(frozen=True, slots=True)
class DeidResult:
    text: str
    entities: list[PhiEntity]
    policy_version: str


class Deidentifier(Protocol):
    @property
    def ready(self) -> bool: ...

    @property
    def policy_version(self) -> str: ...

    def detect(self, text: str) -> list[PhiEntity]: ...

    def anonymize(
        self,
        text: str,
        policy: str = DEFAULT_POLICY,
        document_key: str | None = None,
    ) -> DeidResult: ...


def _validate_policy(policy: str) -> str:
    if policy not in {SAFE_HARBOR_SURROGATE, SAFE_HARBOR_REDACT}:
        raise DeidError("unsupported de-identification policy")
    return policy


def _select_non_overlapping(entities: Sequence[PhiEntity]) -> list[PhiEntity]:
    """Keep the most specific result when recognizers return overlapping spans."""

    selected: list[PhiEntity] = []
    for entity in sorted(
        entities,
        key=lambda item: (item.start, -(item.end - item.start), -item.score, item.entity_type),
    ):
        if entity.start < 0 or entity.end <= entity.start:
            continue
        if any(entity.start < current.end and current.start < entity.end for current in selected):
            continue
        selected.append(entity)
    return sorted(selected, key=lambda item: (item.start, item.end))


def _canonical_entity_type(entity_type: str) -> str:
    aliases = {
        "EMAIL_ADDRESS": "EMAIL",
        "PHONE_NUMBER": "PHONE",
        "US_SSN": "SSN",
        "US_ZIP_CODE": "ZIP",
        "DATE_TIME": "DATE",
        "IP_ADDRESS": "IP_ADDRESS",
        "LOCATION": "LOCATION",
        "PERSON": "PERSON",
    }
    return aliases.get(entity_type.upper(), entity_type.upper())


def _extract_year(value: str) -> str | None:
    match = re.search(r"\b(\d{4})\b", value)
    return match.group(1) if match else None


def _parse_date(value: str) -> tuple[date, str] | None:
    formats = (
        ("%m/%d/%Y", "slash"),
        ("%m/%d/%y", "slash-short"),
        ("%m-%d-%Y", "dash"),
        ("%m-%d-%y", "dash-short"),
        ("%Y-%m-%d", "iso"),
        ("%Y/%m/%d", "iso-slash"),
        ("%B %d, %Y", "long-month"),
        ("%b %d, %Y", "short-month"),
    )
    from datetime import datetime

    for pattern, style in formats:
        try:
            return datetime.strptime(value, pattern).date(), style
        except ValueError:
            continue
    return None


def _format_date(value: date, style: str) -> str:
    if style == "slash":
        return f"{value.month:02d}/{value.day:02d}/{value.year:04d}"
    if style == "slash-short":
        return value.strftime("%m/%d/%y")
    if style == "dash":
        return f"{value.month:02d}-{value.day:02d}-{value.year:04d}"
    if style == "dash-short":
        return value.strftime("%m-%d-%y")
    if style == "iso":
        return value.isoformat()
    if style == "iso-slash":
        return value.strftime("%Y/%m/%d")
    if style == "long-month":
        return value.strftime("%B %d, %Y")
    return value.strftime("%b %d, %Y")


def _date_replacement(original: str, context_key: str | None, salt: bytes) -> str:
    parsed = _parse_date(original)
    if parsed is None:
        return _extract_year(original) or "[DATE]"
    original_date, style = parsed
    if context_key is None:
        # Without a patient/document key, retain only the Safe Harbor year.
        return f"{original_date.year:04d}"
    digest = hmac.new(salt, f"date-shift:{context_key}".encode("utf-8"), hashlib.sha256).digest()
    shift = int.from_bytes(digest[:4], "big") % 731 - 365
    return _format_date(original_date + timedelta(days=shift), style)


def _age_replacement(original: str) -> str:
    match = re.search(r"\b(\d{1,3})\b", original)
    if match and int(match.group(1)) > 89:
        return "90+"
    return original


def _zip_replacement(original: str) -> str:
    digits = re.search(r"\d{5}", original)
    if not digits:
        return "000"
    prefix = digits.group(0)[:3]
    return "000" if prefix in LOW_POPULATION_ZIP3 else prefix


def _context_key(text: str, entities: Sequence[PhiEntity], document_key: str | None) -> str | None:
    if document_key:
        return document_key
    for entity in entities:
        if entity.entity_type in {"MRN", "ACCOUNT_NUMBER", "ENCOUNTER_ID", "PATIENT_ID"}:
            return text[entity.start : entity.end]
    return None


def _anonymize_with_entities(
    text: str,
    entities: Sequence[PhiEntity],
    policy: str,
    salt: bytes,
    policy_version: str,
    document_key: str | None,
) -> DeidResult:
    _validate_policy(policy)
    resolved = _select_non_overlapping(entities)
    context_key = _context_key(text, resolved, document_key)
    output = text
    for entity in reversed(resolved):
        original = output[entity.start : entity.end]
        entity_type = _canonical_entity_type(entity.entity_type)
        if policy == SAFE_HARBOR_REDACT:
            replacement = f"[{entity_type}]"
        elif entity_type == "DATE":
            replacement = _date_replacement(original, context_key, salt)
        elif entity_type == "AGE":
            replacement = _age_replacement(original)
        elif entity_type == "ZIP":
            replacement = _zip_replacement(original)
        else:
            digest = hmac.new(
                salt,
                f"{entity_type}:{original}".encode("utf-8"),
                hashlib.sha256,
            ).hexdigest()[:16]
            replacement = f"{entity_type}_{digest}"
        output = output[: entity.start] + replacement + output[entity.end :]
    return DeidResult(output, resolved, policy_version)


class RegexDeidentifier:
    """Small deterministic backend used only through explicit test injection."""

    policy_version = "regex-safe-harbor-v2"
    patterns = {
        "EMAIL": re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
        "URL": re.compile(r"\bhttps?://[^\s<>\"]+", re.IGNORECASE),
        "PHONE": re.compile(r"\b(?:\+?1[-.\s]?)?(?:\(\d{3}\)|\d{3})[-.\s]\d{3}[-.\s]\d{4}\b"),
        "SSN": re.compile(r"\b\d{3}-\d{2}-\d{4}\b"),
        "IP_ADDRESS": re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
        "MRN": re.compile(
            r"\b(?:MRN|MR#|Medical Record (?:Number|No)|Patient (?:ID|Number)|Pt\.?\s*ID)"
            r"[:#\s-]*[A-Za-z0-9][A-Za-z0-9-]{3,}\b",
            re.IGNORECASE,
        ),
        "ACCOUNT_NUMBER": re.compile(
            r"\b(?:account|acct|FIN)\s*(?:number|no\.?|#)?\s*[:#-]?\s*[A-Za-z0-9-]{4,}\b",
            re.IGNORECASE,
        ),
        "ENCOUNTER_ID": re.compile(
            r"\b(?:encounter|visit|CSN)\s*(?:number|no\.?|#)?\s*[:#-]?\s*[A-Za-z0-9-]{4,}\b",
            re.IGNORECASE,
        ),
        "DEVICE_ID": re.compile(
            r"\b(?:device|serial|UDI)\s*(?:serial|number|no\.?|id|#)?\s*[:#-]?\s*[A-Za-z0-9-]{5,}\b",
            re.IGNORECASE,
        ),
        "FACILITY": re.compile(
            r"\b(?:[A-Z][A-Za-z0-9&'/-]*\s+){0,6}"
            r"(?:Hospital|Medical Center|Clinic|Health System|Healthcare)\b"
        ),
        "ZIP": re.compile(r"\b\d{5}(?:-\d{4})?\b"),
        "DATE": re.compile(
            r"\b(?:\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2}|"
            r"(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            r"Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|"
            r"Dec(?:ember)?)\s+\d{1,2},\s+\d{4})\b",
            re.IGNORECASE,
        ),
        "AGE": re.compile(
            r"\b(?:age\s*[:=]?\s*)?\d{1,3}(?:\s*[- ]?year[- ]old|\s*y/o|\s*years?\s*old)\b",
            re.IGNORECASE,
        ),
    }

    def __init__(self, salt: str) -> None:
        if not salt:
            raise ValueError("HMAC salt is required")
        self._salt = salt.encode("utf-8")

    @property
    def ready(self) -> bool:
        return True

    def detect(self, text: str) -> list[PhiEntity]:
        entities: list[PhiEntity] = []
        for entity_type, pattern in self.patterns.items():
            for match in pattern.finditer(text):
                entities.append(PhiEntity(entity_type, match.start(), match.end(), 0.95, "regex"))
        return _select_non_overlapping(entities)

    def anonymize(
        self,
        text: str,
        policy: str = DEFAULT_POLICY,
        document_key: str | None = None,
    ) -> DeidResult:
        return _anonymize_with_entities(
            text,
            self.detect(text),
            policy,
            self._salt,
            self.policy_version,
            document_key,
        )


class PresidioDeidentifier:
    """Production backend backed by Presidio Analyzer and its recognizer registry."""

    def __init__(self, analyzer: Any, salt: str, model_name: str, model_version: str) -> None:
        if not salt:
            raise DeidInitializationError("HMAC salt is required")
        self._analyzer = analyzer
        self._salt = salt.encode("utf-8")
        self._ready = False
        self.policy_version = f"presidio-safe-harbor-v2;{model_name}@{model_version}"

    @property
    def ready(self) -> bool:
        return self._ready

    def warmup(self) -> None:
        try:
            self._analyzer.analyze("warmup@example.com", language="en")
        except Exception as exc:
            raise DeidInitializationError("Presidio analyzer warmup failed") from exc
        self._ready = True

    def detect(self, text: str) -> list[PhiEntity]:
        if not self.ready:
            raise DeidUnavailableError("Presidio analyzer is not ready")
        try:
            results = self._analyzer.analyze(text=text, language="en")
        except Exception as exc:
            raise DeidError("Presidio analysis failed") from exc
        entities: list[PhiEntity] = []
        for result in results:
            metadata = getattr(result, "recognition_metadata", {})
            recognizer = "presidio"
            if isinstance(metadata, Mapping):
                recognizer = str(metadata.get("recognizer_name", recognizer))
            entities.append(
                PhiEntity(
                    _canonical_entity_type(str(result.entity_type)),
                    int(result.start),
                    int(result.end),
                    float(result.score),
                    recognizer,
                )
            )
        return _select_non_overlapping(entities)

    def anonymize(
        self,
        text: str,
        policy: str = DEFAULT_POLICY,
        document_key: str | None = None,
    ) -> DeidResult:
        return _anonymize_with_entities(
            text,
            self.detect(text),
            policy,
            self._salt,
            self.policy_version,
            document_key,
        )


class FailClosedDeidentifier:
    """Backend installed when production initialization fails."""

    policy_version = "deid-unavailable-v1"

    @property
    def ready(self) -> bool:
        return False

    def detect(self, text: str) -> list[PhiEntity]:
        raise DeidUnavailableError("de-identification backend is unavailable")

    def anonymize(
        self,
        text: str,
        policy: str = DEFAULT_POLICY,
        document_key: str | None = None,
    ) -> DeidResult:
        raise DeidUnavailableError("de-identification backend is unavailable")


def _custom_recognizers() -> list[Any]:
    from presidio_analyzer import Pattern, PatternRecognizer

    patterns: dict[str, str] = {
        "MRN": r"\b(?:MRN|MR#|Medical Record (?:Number|No)|Patient (?:ID|Number)|Pt\.?\s*ID)[:#\s-]*[A-Za-z0-9][A-Za-z0-9-]{3,}\b",
        "ACCOUNT_NUMBER": r"\b(?:account|acct|FIN)\s*(?:number|no\.?|#)?\s*[:#-]?\s*[A-Za-z0-9-]{4,}\b",
        "ENCOUNTER_ID": r"\b(?:encounter|visit|CSN)\s*(?:number|no\.?|#)?\s*[:#-]?\s*[A-Za-z0-9-]{4,}\b",
        "FACILITY": r"\b(?:[A-Z][A-Za-z0-9&'/-]*\s+){0,6}(?:Hospital|Medical Center|Clinic|Health System|Healthcare)\b",
        "DEVICE_ID": r"\b(?:device|serial|UDI)\s*(?:serial|number|no\.?|id|#)?\s*[:#-]?\s*[A-Za-z0-9-]{5,}\b",
        "AGE": r"\b(?:age\s*[:=]?\s*)?\d{1,3}(?:\s*[- ]?year[- ]old|\s*y/o|\s*years?\s*old)\b",
        "DATE": r"\b(?:\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}[/-]\d{1,2}[/-]\d{1,2})\b",
    }
    return [
        PatternRecognizer(
            name=f"{entity_type}Recognizer",
            supported_entity=entity_type,
            patterns=[Pattern(name=f"{entity_type}Pattern", regex=regex, score=0.9)],
        )
        for entity_type, regex in patterns.items()
    ]


def build_production_deidentifier(settings: Any) -> PresidioDeidentifier:
    """Create and warm the real backend; callers must turn failures into NOT_SERVING."""

    if not settings.hmac_salt:
        raise DeidInitializationError("MEDASSIST_HMAC_SALT is required")
    try:
        from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
        from presidio_analyzer.nlp_engine import NlpEngineProvider
    except ImportError as exc:
        raise DeidInitializationError("Presidio dependencies are not installed") from exc

    configuration = {
        "nlp_engine_name": "spacy",
        "models": [{"lang_code": "en", "model_name": settings.presidio_model_name}],
    }
    try:
        provider = NlpEngineProvider(nlp_configuration=configuration)
        nlp_engine = provider.create_engine()
        registry = RecognizerRegistry()
        registry.load_predefined_recognizers()
        for recognizer in _custom_recognizers():
            registry.add_recognizer(recognizer)
        analyzer = AnalyzerEngine(
            registry=registry,
            nlp_engine=nlp_engine,
            supported_languages=["en"],
        )
        backend = PresidioDeidentifier(
            analyzer,
            settings.hmac_salt,
            settings.presidio_model_name,
            settings.presidio_model_version,
        )
        backend.warmup()
        return backend
    except DeidInitializationError:
        raise
    except Exception as exc:
        raise DeidInitializationError("Presidio analyzer initialization failed") from exc
