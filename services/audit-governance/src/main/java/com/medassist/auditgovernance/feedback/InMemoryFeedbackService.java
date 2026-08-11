package com.medassist.auditgovernance.feedback;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditEventPublisher;
import com.medassist.auditgovernance.AuditPayload;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M4 feedback boundary. It records structured feedback and only creates candidates after an
 * explicit reviewer decision; no method automatically changes an evaluation set or retrieval.
 */
public final class InMemoryFeedbackService {
  private final ConcurrentMap<UUID, FeedbackRecord> records = new ConcurrentHashMap<>();
  private final AuditEventPublisher auditPublisher;
  private final DeidentifiedTextGuard textGuard;
  private final Clock clock;

  public InMemoryFeedbackService(
      final AuditEventPublisher auditPublisher,
      final DeidentifiedTextGuard textGuard,
      final Clock clock) {
    this.auditPublisher = Objects.requireNonNull(auditPublisher, "auditPublisher");
    this.textGuard = Objects.requireNonNull(textGuard, "textGuard");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public FeedbackRecord submit(
      final String subject, final String actorRole, final FeedbackSubmission submission) {
    final FeedbackRecord record =
        new FeedbackRecord(
            UUID.randomUUID(), submission, Instant.now(clock), FeedbackStatus.PENDING_REVIEW);
    records.put(record.id(), record);
    audit(subject, actorRole, "feedback.submit", record, "ACCEPTED");
    return record;
  }

  public List<FeedbackRecord> pendingQueue() {
    return records.values().stream()
        .filter(record -> record.status() == FeedbackStatus.PENDING_REVIEW)
        .sorted(
            Comparator.comparing(
                    (FeedbackRecord record) ->
                        record.submission().issueCategory() == FeedbackIssueCategory.SAFETY_CONCERN
                            ? 0
                            : 1)
                .thenComparing(
                    Comparator.comparing((FeedbackRecord record) -> record.submission().severity())
                        .reversed())
                .thenComparing(FeedbackRecord::submittedAt))
        .toList();
  }

  public EvaluationCandidate review(
      final String reviewerSubject,
      final String reviewerRole,
      final UUID feedbackId,
      final FeedbackReviewDecision decision,
      final String deidentifiedAnswer,
      final List<SupportingSpan> supportingSpans) {
    if (!"ADMIN".equals(reviewerRole)) {
      throw new FeedbackAccessDeniedException("feedback review requires ADMIN");
    }
    final FeedbackRecord record = records.get(feedbackId);
    if (record == null || record.status() != FeedbackStatus.PENDING_REVIEW) {
      throw new FeedbackNotFoundException("pending feedback not found");
    }
    if (decision == FeedbackReviewDecision.MARK_REVIEWED) {
      final FeedbackRecord reviewed = record.withStatus(FeedbackStatus.REVIEWED);
      records.put(feedbackId, reviewed);
      audit(reviewerSubject, reviewerRole, "feedback.review", reviewed, "REVIEWED");
      return null;
    }
    final String safeAnswer = textGuard.requireDeidentified(deidentifiedAnswer);
    final EvaluationCandidate candidate =
        new EvaluationCandidate(
            feedbackId, record.submission().traceId(), safeAnswer, supportingSpans);
    records.put(feedbackId, record.withStatus(FeedbackStatus.CANDIDATE_CREATED));
    audit(reviewerSubject, reviewerRole, "feedback.candidate.create", record, "CANDIDATE_CREATED");
    return candidate;
  }

  private void audit(
      final String actor,
      final String actorRole,
      final String action,
      final FeedbackRecord record,
      final String outcome) {
    auditPublisher.publish(
        new AuditEvent(
            UUID.randomUUID(),
            Instant.now(clock),
            actor,
            actorRole,
            action,
            "feedback",
            record.id().toString(),
            outcome,
            AuditPayload.of(
                java.util.Map.of(
                    "traceId", record.submission().traceId(), "resultCode", outcome))));
  }
}
