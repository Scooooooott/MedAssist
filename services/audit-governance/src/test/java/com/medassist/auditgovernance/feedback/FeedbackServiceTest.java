package com.medassist.auditgovernance.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.auditgovernance.InMemoryAuditEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void structuredFeedbackIsAuditedAndSafetyIssuesComeFirst() {
    final InMemoryAuditEventPublisher audit = new InMemoryAuditEventPublisher();
    final InMemoryFeedbackService service = new InMemoryFeedbackService(audit, text -> text, CLOCK);
    service.submit("user-1", "CLINICIAN", submission("trace-low", FeedbackSeverity.LOW));
    service.submit("user-2", "RESEARCHER", submission("trace-critical", FeedbackSeverity.CRITICAL));

    assertEquals("trace-critical", service.pendingQueue().getFirst().submission().traceId());
    assertEquals(2, audit.events().size());
  }

  @Test
  void onlyAdminCanReviewAndPromotionIsExplicit() {
    final InMemoryFeedbackService service =
        new InMemoryFeedbackService(new InMemoryAuditEventPublisher(), text -> text, CLOCK);
    final FeedbackRecord record =
        service.submit("user-1", "CLINICIAN", submission("trace-1", FeedbackSeverity.HIGH));
    assertThrows(
        FeedbackAccessDeniedException.class,
        () ->
            service.review(
                "user-2",
                "RESEARCHER",
                record.id(),
                FeedbackReviewDecision.MARK_REVIEWED,
                null,
                List.of()));

    final EvaluationCandidate candidate =
        service.review(
            "admin",
            "ADMIN",
            record.id(),
            FeedbackReviewDecision.CREATE_EVALUATION_CANDIDATE,
            "deidentified answer",
            List.of(new SupportingSpan("doc-1", 0, 12)));
    assertEquals("trace-1", candidate.traceId());
    assertTrue(service.pendingQueue().isEmpty());
  }

  @Test
  void freeTextIsRejectedByTheConfiguredGuard() {
    final InMemoryFeedbackService service =
        new InMemoryFeedbackService(
            new InMemoryAuditEventPublisher(),
            text -> {
              throw new IllegalArgumentException("de-identification required");
            },
            CLOCK);
    final FeedbackRecord record =
        service.submit("user-1", "CLINICIAN", submission("trace-2", FeedbackSeverity.MEDIUM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.review(
                "admin",
                "ADMIN",
                record.id(),
                FeedbackReviewDecision.CREATE_EVALUATION_CANDIDATE,
                "raw patient text",
                List.of()));
  }

  private static FeedbackSubmission submission(
      final String traceId, final FeedbackSeverity severity) {
    return new FeedbackSubmission(
        traceId,
        FeedbackOverallRating.NEGATIVE,
        List.of(new CitationFeedback("citation-1", CitationRating.NOT_RELEVANT)),
        FeedbackIssueCategory.SAFETY_CONCERN,
        severity);
  }
}
