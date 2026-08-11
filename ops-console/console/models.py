from django.db import models


class FeedbackReviewQueue(models.Model):
    """Read-only inspectdb-style projection for the feedback review queue."""

    id = models.UUIDField(primary_key=True)
    trace_id = models.CharField(max_length=128)
    category = models.CharField(max_length=64)
    severity = models.CharField(max_length=32)
    status = models.CharField(max_length=32)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "feedback_review_queue"
        app_label = "console"


class QuarantineQueueItem(models.Model):
    """Read-only safe-failure metadata projection."""

    id = models.UUIDField(primary_key=True)
    document_id = models.UUIDField(null=True)
    document_version_id = models.UUIDField(null=True)
    source_uri = models.CharField(max_length=2048)
    failure_stage = models.CharField(max_length=128)
    failure_reason = models.CharField(max_length=2048)
    created_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "quarantine"
        app_label = "console"


class DocumentMetadataReviewQueue(models.Model):
    """Read-only projection for human confirmation of safe metadata fields."""

    id = models.UUIDField(primary_key=True)
    document_version_id = models.UUIDField()
    missing_fields = models.JSONField()
    status = models.CharField(max_length=32)
    reason_code = models.CharField(max_length=64)
    created_at = models.DateTimeField()
    resolved_at = models.DateTimeField(null=True)

    class Meta:
        managed = False
        db_table = "document_metadata_review"
        app_label = "console"


class EvaluationCandidateQueue(models.Model):
    """Placeholder for the Java-owned evaluation candidate projection."""

    id = models.UUIDField(primary_key=True)
    source_feedback_id = models.UUIDField(null=True)
    status = models.CharField(max_length=32)
    severity = models.CharField(max_length=32)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "evaluation_candidate"
        app_label = "console"
