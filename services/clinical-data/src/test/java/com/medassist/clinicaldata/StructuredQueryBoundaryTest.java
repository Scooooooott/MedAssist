package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.clinicaldata.query.DefaultStructuredQueryBoundary;
import com.medassist.clinicaldata.query.StructuredQueryAccessDeniedException;
import com.medassist.clinicaldata.query.StructuredQueryException;
import com.medassist.clinicaldata.query.StructuredQueryRequest;
import com.medassist.clinicaldata.query.StructuredView;
import com.medassist.domain.Role;
import org.junit.jupiter.api.Test;

class StructuredQueryBoundaryTest {
  private final DefaultStructuredQueryBoundary boundary =
      new DefaultStructuredQueryBoundary(ClinicalQueryProperties.defaults());

  @Test
  void acceptsAllowListedResearchAggregate() {
    assertThatCode(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER,
                        "select condition_code, count(*) from clinical_research_condition_counts limit 20")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMutationCommentsAndUnboundedQueries() {
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER, "delete from clinical_research_condition_counts limit 1")))
        .isInstanceOf(StructuredQueryException.class);
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER,
                        "select count(*) from clinical_research_condition_counts -- x limit 1")))
        .isInstanceOf(StructuredQueryException.class);
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER,
                        "select count(*) from clinical_research_condition_counts")))
        .isInstanceOf(StructuredQueryException.class);
  }

  @Test
  void rejectsWrongViewAndNonAggregateResearchQuery() {
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER,
                        "select count(*) from clinical_research_observation_counts limit 1")))
        .isInstanceOf(StructuredQueryException.class);
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER,
                        "select condition_code from clinical_research_condition_counts limit 1")))
        .isInstanceOf(StructuredQueryException.class);
  }

  @Test
  void rejectsAdminAndInvalidClinicalExemption() {
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.ADMIN,
                        "select count(*) from clinical_research_condition_counts limit 1")))
        .isInstanceOf(StructuredQueryAccessDeniedException.class);
    final StructuredQueryRequest researcherExemption =
        new StructuredQueryRequest(
            "actor",
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts limit 1",
            true,
            "reason");
    assertThatThrownBy(() -> boundary.validate(researcherExemption))
        .isInstanceOf(StructuredQueryAccessDeniedException.class);
  }

  private static StructuredQueryRequest request(final Role role, final String sql) {
    return new StructuredQueryRequest(
        "actor", role, StructuredView.CONDITION_COUNTS, sql, false, null);
  }
}
