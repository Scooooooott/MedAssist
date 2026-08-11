package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.clinicaldata.research.ClinicalQueryAuditSink;
import com.medassist.clinicaldata.research.LoggingClinicalQueryAuditSink;
import com.medassist.clinicaldata.research.ResearchAggregateRepository;
import com.medassist.clinicaldata.research.ResearchAggregateRow;
import com.medassist.clinicaldata.research.ResearchQueryAccessDeniedException;
import com.medassist.clinicaldata.research.ResearchQueryAuditEvent;
import com.medassist.clinicaldata.research.ResearchQueryResult;
import com.medassist.clinicaldata.research.ResearchView;
import com.medassist.clinicaldata.research.ResearchViewQuery;
import com.medassist.clinicaldata.research.ResearchViewService;
import com.medassist.clinicaldata.research.UnavailableResearchAggregateRepository;
import com.medassist.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchViewServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void suppressesSmallGroupsTruncatesRowsAndAuditsAllowedQueries() {
    final ResearchAggregateRepository repository = mock();
    final ClinicalQueryAuditSink auditSink = mock();
    final ClinicalQueryProperties properties =
        new ClinicalQueryProperties(5, 1_000, 1, true, java.util.Set.of());
    when(repository.find(ResearchViewQuery.researcher(ResearchView.CONDITION_COUNTS)))
        .thenReturn(
            List.of(
                new ResearchAggregateRow(Map.of("code", "small"), 2),
                new ResearchAggregateRow(Map.of("code", "visible"), 5),
                new ResearchAggregateRow(Map.of("code", "extra"), 6)));

    final ResearchQueryResult result =
        new ResearchViewService(properties, repository, auditSink, CLOCK)
            .query(
                "researcher-1",
                Role.RESEARCHER,
                ResearchViewQuery.researcher(ResearchView.CONDITION_COUNTS));

    assertThat(result.rows())
        .extracting(row -> row.dimensions().get("code"))
        .containsExactly("visible");
    assertThat(result.kAnonymity()).isEqualTo(5);
    assertThat(result.suppressedGroupCount()).isEqualTo(1);
    assertThat(result.truncated()).isTrue();
    verify(auditSink)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    event.timestamp().equals(Instant.parse("2025-01-01T00:00:00Z"))
                        && event.returnedRows() == 1
                        && event.suppressedGroups() == 1
                        && !event.clinicalExemption()));
  }

  @Test
  void clinicianExemptionRevealsSmallGroupsAndRecordsReason() {
    final ResearchAggregateRepository repository = mock();
    final ClinicalQueryAuditSink auditSink = mock();
    final ResearchViewQuery query =
        new ResearchViewQuery(
            ResearchView.OBSERVATION_COUNTS, Map.of("year", "2024"), true, "care review");
    when(repository.find(query))
        .thenReturn(List.of(new ResearchAggregateRow(Map.of("year", "2024"), 1)));

    final ResearchQueryResult result =
        new ResearchViewService(ClinicalQueryProperties.defaults(), repository, auditSink, CLOCK)
            .query("clinician-1", Role.CLINICIAN, query);

    assertThat(result.rows()).hasSize(1);
    assertThat(result.kAnonymityExempt()).isTrue();
    assertThat(result.suppressedGroupCount()).isZero();
    verify(auditSink)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    event.clinicalExemption()
                        && event.exemptionReason().equals("care review")
                        && event.role() == Role.CLINICIAN));
  }

  @Test
  void deniesAdminAndResearcherClinicalExemptionBeforeRepositoryAccess() {
    final ResearchAggregateRepository repository = mock();
    final ClinicalQueryAuditSink auditSink = mock();
    final ResearchViewService service =
        new ResearchViewService(ClinicalQueryProperties.defaults(), repository, auditSink, CLOCK);
    final ResearchViewQuery exemption =
        new ResearchViewQuery(ResearchView.CONDITION_COUNTS, Map.of(), true, "reason");

    assertThatThrownBy(
            () ->
                service.query(
                    "admin",
                    Role.ADMIN,
                    ResearchViewQuery.researcher(ResearchView.CONDITION_COUNTS)))
        .isInstanceOf(ResearchQueryAccessDeniedException.class);
    assertThatThrownBy(() -> service.query("researcher", Role.RESEARCHER, exemption))
        .isInstanceOf(ResearchQueryAccessDeniedException.class);
    org.mockito.Mockito.verifyNoInteractions(repository, auditSink);
  }

  @Test
  void explicitUnavailableRepositoryFailsClosedAndAuditSinkAcceptsSafeMetadata() {
    assertThatThrownBy(
            () ->
                new UnavailableResearchAggregateRepository()
                    .find(ResearchViewQuery.researcher(ResearchView.CONDITION_COUNTS)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");

    final ResearchQueryAuditEvent event =
        new ResearchQueryAuditEvent(
            Instant.EPOCH,
            "actor",
            Role.RESEARCHER,
            ResearchView.CONDITION_COUNTS,
            "DENIED",
            0,
            0,
            false,
            null);
    new LoggingClinicalQueryAuditSink().record(event);
  }
}
