import pytest

from deid_svc.core import (
    DeidError,
    DeidInitializationError,
    DeidUnavailableError,
    FailClosedDeidentifier,
    PhiEntity,
    PresidioDeidentifier,
    RegexDeidentifier,
)


def test_anonymize_removes_original_value_and_keeps_metadata_only() -> None:
    deid = RegexDeidentifier("test-salt")
    result = deid.anonymize("Contact MRN: AB12345 at jane@example.com")

    assert "jane@example.com" not in result.text
    assert "AB12345" not in result.text
    assert {entity.entity_type for entity in result.entities} == {"EMAIL", "MRN"}
    assert not hasattr(result.entities[0], "value")


def test_same_value_maps_consistently() -> None:
    deid = RegexDeidentifier("test-salt")

    first = deid.anonymize("a@example.com").text
    second = deid.anonymize("a@example.com").text

    assert first == second


def test_safe_harbor_special_fields_and_redact_policy() -> None:
    deid = RegexDeidentifier("test-salt")
    result = deid.anonymize(
        "Age 92 years old, born 01/02/2020, ZIP 03612, normal ZIP 94107, email a@example.com",
        document_key="patient-1",
    )
    assert "90+" in result.text
    assert "03612" not in result.text
    assert "036" not in result.text
    assert "941" in result.text
    assert "01/02/2020" not in result.text

    redacted = deid.anonymize("a@example.com", policy="SAFE_HARBOR_REDACT")
    assert redacted.text == "[EMAIL]"
    with pytest.raises(DeidError):
        deid.anonymize("a@example.com", policy="unsupported")


class FakeResult:
    entity_type = "EMAIL_ADDRESS"
    start = 0
    end = 16
    score = 0.8
    recognition_metadata = {"recognizer_name": "fake-recognizer"}


class FakeAnalyzer:
    def __init__(self, fail: bool = False) -> None:
        self.fail = fail

    def analyze(self, text: str, language: str) -> list[FakeResult]:
        if self.fail:
            raise RuntimeError("fake failure")
        return [FakeResult()]


def test_presidio_backend_warmup_and_metadata_only_entities() -> None:
    backend = PresidioDeidentifier(FakeAnalyzer(), "salt", "fake-model", "v1")
    assert not backend.ready
    with pytest.raises(DeidUnavailableError):
        backend.detect("a@example.com")
    backend.warmup()
    assert backend.ready
    entities = backend.detect("a@example.com")
    assert entities == [PhiEntity("EMAIL", 0, 16, 0.8, "fake-recognizer")]
    result = backend.anonymize("a@example.com")
    assert "a@example.com" not in result.text
    assert "fake-model@v1" in result.policy_version


def test_presidio_backend_fails_closed_on_analysis_error() -> None:
    backend = PresidioDeidentifier(FakeAnalyzer(fail=True), "salt", "fake-model", "v1")
    with pytest.raises(DeidInitializationError):
        backend.warmup()


def test_fail_closed_backend_never_returns_text() -> None:
    backend = FailClosedDeidentifier()
    assert not backend.ready
    with pytest.raises(DeidUnavailableError):
        backend.detect("secret")
    with pytest.raises(DeidUnavailableError):
        backend.anonymize("secret")
