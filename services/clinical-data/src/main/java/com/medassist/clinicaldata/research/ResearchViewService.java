package com.medassist.clinicaldata.research;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ResearchViewService {
  private final ClinicalQueryProperties properties;
  private final ResearchAggregateRepository repository;
  private final ClinicalQueryAuditSink auditSink;
  private final Clock clock;

  @Autowired
  public ResearchViewService(
      final ClinicalQueryProperties properties,
      final ResearchAggregateRepository repository,
      final ClinicalQueryAuditSink auditSink) {
    this(properties, repository, auditSink, Clock.systemUTC());
  }

  public ResearchViewService(
      final ClinicalQueryProperties properties,
      final ResearchAggregateRepository repository,
      final ClinicalQueryAuditSink auditSink,
      final Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ResearchQueryResult query(
      final String actor, final Role role, final ResearchViewQuery query) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(query, "query");
    authorize(role, query);
    final List<ResearchAggregateRow> sourceRows = repository.find(query);
    final boolean exempt = role == Role.CLINICIAN && query.clinicalExemption();
    final List<ResearchAggregateRow> visibleRows = new ArrayList<>();
    int suppressed = 0;
    for (final ResearchAggregateRow row : sourceRows) {
      if (!exempt && row.patientCount() < properties.kAnonymity()) {
        suppressed++;
      } else if (visibleRows.size() < properties.maxRows()) {
        visibleRows.add(row);
      }
    }
    final boolean truncated = visibleRows.size() < sourceRows.size() - suppressed;
    final ResearchQueryResult result =
        new ResearchQueryResult(
            query.view(), visibleRows, properties.kAnonymity(), exempt, suppressed, truncated);
    auditSink.record(
        new ResearchQueryAuditEvent(
            Instant.now(clock),
            actor,
            role,
            query.view(),
            "ALLOWED",
            result.rows().size(),
            suppressed,
            exempt,
            exempt ? query.exemptionReason() : null));
    return result;
  }

  private static void authorize(final Role role, final ResearchViewQuery query) {
    if (role == Role.ADMIN) {
      throw new ResearchQueryAccessDeniedException("ADMIN is not a research query role");
    }
    if (role == Role.RESEARCHER && query.clinicalExemption()) {
      throw new ResearchQueryAccessDeniedException("clinical exemption is limited to CLINICIAN");
    }
  }
}
