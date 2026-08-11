package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.clinicaldata.query.DefaultStructuredQueryBoundary;
import com.medassist.clinicaldata.query.FailClosedStructuredQueryBoundary;
import com.medassist.clinicaldata.query.JdbcStructuredQueryExecutor;
import com.medassist.clinicaldata.query.StructuredQueryAccessDeniedException;
import com.medassist.clinicaldata.query.StructuredQueryException;
import com.medassist.clinicaldata.query.StructuredQueryRequest;
import com.medassist.clinicaldata.query.StructuredQueryResult;
import com.medassist.clinicaldata.query.StructuredQueryService;
import com.medassist.clinicaldata.query.StructuredView;
import com.medassist.domain.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

class StructuredQuerySafetyTest {
  private static final ClinicalQueryProperties PROPERTIES = ClinicalQueryProperties.defaults();

  @Test
  void defaultBoundaryCoversRoleViewLimitAndForbiddenOperationGuards() {
    final DefaultStructuredQueryBoundary boundary = new DefaultStructuredQueryBoundary(PROPERTIES);
    assertThatCode(
            () ->
                boundary.validate(
                    request(
                        Role.CLINICIAN,
                        StructuredView.CONDITION_COUNTS,
                        "select condition_code from clinical_research_condition_counts limit 1",
                        false)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                boundary.validate(
                    request(
                        Role.CLINICIAN,
                        StructuredView.CONDITION_COUNTS,
                        "select count(*) from clinical_research_condition_counts limit 1",
                        true)))
        .doesNotThrowAnyException();

    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts limit 0",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts limit 101",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER, StructuredView.CONDITION_COUNTS, "select count(*) limit 1", false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts; select 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts /* comment */ limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts union select count(*) from clinical_research_condition_counts limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts join clinical_patient on true limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts, clinical_patient limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts -- comment limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select pg_read_file('/etc/passwd') from clinical_research_condition_counts limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_observation_counts limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.RESEARCHER,
            StructuredView.CONDITION_COUNTS,
            "select condition_code from clinical_research_condition_counts limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.ADMIN,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts limit 1",
            false));
    assertThatThrownBy(
            () ->
                boundary.validate(
                    new StructuredQueryRequest(
                        "actor",
                        Role.RESEARCHER,
                        StructuredView.CONDITION_COUNTS,
                        "select count(*) from clinical_research_condition_counts limit 1",
                        true,
                        "reason")))
        .isInstanceOf(StructuredQueryAccessDeniedException.class);
  }

  @Test
  void failClosedBoundaryRejectsUnsafeShapesAndAllowsOnlyConfiguredViews() {
    final FailClosedStructuredQueryBoundary boundary =
        new FailClosedStructuredQueryBoundary(PROPERTIES);
    assertThatCode(
            () ->
                boundary.validate(
                    request(
                        Role.CLINICIAN,
                        StructuredView.CONDITION_COUNTS,
                        "select condition_code from clinical_research_condition_counts limit 1",
                        false)))
        .doesNotThrowAnyException();
    assertRejected(
        boundary,
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select * from clinical_research_condition_counts",
            false));
    assertRejected(
        boundary,
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select * from clinical_research_condition_counts limit 1;",
            false));
    assertRejected(
        boundary,
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "update clinical_research_condition_counts set x=1 limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select * from clinical_research_condition_counts where x in (select x from clinical_research_condition_counts) limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.CLINICIAN,
            StructuredView.OBSERVATION_COUNTS,
            "select * from clinical_research_condition_counts limit 1",
            false));
    assertRejected(
        boundary,
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select * from clinical_research_condition_counts limit 101",
            false));
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.ADMIN,
                        StructuredView.CONDITION_COUNTS,
                        "select * from clinical_research_condition_counts limit 1",
                        false)))
        .isInstanceOf(StructuredQueryAccessDeniedException.class);
    assertThatThrownBy(
            () ->
                boundary.validate(
                    request(
                        Role.RESEARCHER,
                        StructuredView.CONDITION_COUNTS,
                        "select count(*) from clinical_research_condition_counts limit 1",
                        true)))
        .isInstanceOf(StructuredQueryAccessDeniedException.class);

    final ClinicalQueryProperties onlyCondition =
        new ClinicalQueryProperties(
            2, 1_000, 2, true, Set.of(StructuredView.CONDITION_COUNTS.sqlName()));
    final FailClosedStructuredQueryBoundary restricted =
        new FailClosedStructuredQueryBoundary(onlyCondition);
    assertRejected(
        restricted,
        request(
            Role.CLINICIAN,
            StructuredView.OBSERVATION_COUNTS,
            "select count(*) from clinical_research_observation_counts limit 1",
            false));
  }

  @Test
  void serviceValidatesBeforeDelegatingToExecutor() {
    final com.medassist.clinicaldata.query.StructuredQueryBoundary boundary = mock();
    final com.medassist.clinicaldata.query.StructuredQueryExecutor executor = mock();
    final StructuredQueryRequest request =
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select count(*) from clinical_research_condition_counts limit 1",
            false);
    final StructuredQueryResult result =
        new StructuredQueryResult(
            StructuredView.CONDITION_COUNTS,
            java.util.List.of(),
            java.util.List.of(),
            false,
            false);
    when(executor.execute(request)).thenReturn(result);
    assertThat(new StructuredQueryService(boundary, executor).execute(request)).isSameAs(result);
    verify(boundary).validate(request);
    verify(executor).execute(request);
    assertThatThrownBy(() -> new StructuredQueryService(null, executor))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StructuredQueryService(boundary, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void jdbcExecutorHandlesCountAliasesIdentifiersExemptionsAndMissingCounts() throws Exception {
    final StructuredQueryRequest request =
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select condition_code, patient_id, patient_count from clinical_research_condition_counts limit 20",
            false);
    final PreparedStatement statement =
        execute(
            request,
            resultSet(
                new String[] {"condition_code", "patient_id", "patient_count"},
                new Object[][] {{"C01", "p-1", 5L}}),
            PROPERTIES);
    verify(statement).setQueryTimeout(5);

    final ResultSet resultSet =
        resultSet(
            new String[] {"condition_code", "patient_id", "patient_count"},
            new Object[][] {{"C01", "p-1", 5L}});
    final StructuredQueryResult result =
        new JdbcStructuredQueryExecutor(
                jdbcReturning(resultSet, statement),
                new DefaultStructuredQueryBoundary(PROPERTIES),
                PROPERTIES)
            .execute(request);
    assertThat(result.rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.dimensions()).containsEntry("condition_code", "C01");
              assertThat(row.dimensions()).doesNotContainKey("patient_id");
              assertThat(row.count()).isEqualTo(5);
            });

    final StructuredQueryRequest exempt =
        request(
            Role.CLINICIAN,
            StructuredView.CONDITION_COUNTS,
            "select condition_code, aggregate_count from clinical_research_condition_counts limit 1",
            true);
    final ResultSet small =
        resultSet(new String[] {"condition_code", "aggregate_count"}, new Object[][] {{"C02", 1L}});
    final StructuredQueryResult exemptResult =
        new JdbcStructuredQueryExecutor(
                jdbcReturning(small, mock(PreparedStatement.class)),
                new DefaultStructuredQueryBoundary(PROPERTIES),
                PROPERTIES)
            .execute(exempt);
    assertThat(exemptResult.rows()).hasSize(1);
    assertThat(exemptResult.kAnonymityExempt()).isTrue();

    final ResultSet missingCount =
        resultSet(new String[] {"condition_code"}, new Object[][] {{"C03"}});
    assertThatThrownBy(
            () ->
                new JdbcStructuredQueryExecutor(
                        jdbcReturning(missingCount, mock(PreparedStatement.class)),
                        new DefaultStructuredQueryBoundary(PROPERTIES),
                        PROPERTIES)
                    .execute(request))
        .isInstanceOf(StructuredQueryException.class)
        .hasMessageContaining("count column");
  }

  @Test
  void patientCountWinsOverAggregateCountForKAnonymity() throws Exception {
    final StructuredQueryRequest request = validRequest();
    final ResultSet resultSet =
        resultSet(
            new String[] {"condition_code", "aggregate_count", "patient_count"},
            new Object[][] {{"C01", 100L, 1L}});

    final StructuredQueryResult result =
        new JdbcStructuredQueryExecutor(
                jdbcReturning(resultSet, mock(PreparedStatement.class)),
                new DefaultStructuredQueryBoundary(PROPERTIES),
                PROPERTIES)
            .execute(request);

    assertThat(result.rows()).isEmpty();
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void jdbcExecutorUsesCeilingAndMinimumTimeout() throws Exception {
    final PreparedStatement one =
        execute(
            validRequest(),
            resultSet(new String[] {"count"}, new Object[][] {{5L}}),
            new ClinicalQueryProperties(2, 1, 100, true, PROPERTIES.allowedViews()));
    verify(one).setQueryTimeout(1);
    final PreparedStatement two =
        execute(
            validRequest(),
            resultSet(new String[] {"count"}, new Object[][] {{5L}}),
            new ClinicalQueryProperties(2, 1_001, 100, true, PROPERTIES.allowedViews()));
    verify(two).setQueryTimeout(2);
  }

  private static void assertRejected(
      final com.medassist.clinicaldata.query.StructuredQueryBoundary boundary,
      final StructuredQueryRequest request) {
    assertThatThrownBy(() -> boundary.validate(request))
        .isInstanceOf(StructuredQueryException.class);
  }

  private static StructuredQueryRequest validRequest() {
    return request(
        Role.RESEARCHER,
        StructuredView.CONDITION_COUNTS,
        "select count(*) from clinical_research_condition_counts limit 1",
        false);
  }

  private static StructuredQueryRequest request(
      final Role role, final StructuredView view, final String sql, final boolean exemption) {
    return new StructuredQueryRequest(
        "actor", role, view, sql, exemption, exemption ? "reason" : null);
  }

  private static PreparedStatement execute(
      final StructuredQueryRequest request,
      final ResultSet resultSet,
      final ClinicalQueryProperties properties)
      throws Exception {
    final PreparedStatement statement = mock(PreparedStatement.class);
    new JdbcStructuredQueryExecutor(
            jdbcReturning(resultSet, statement),
            new DefaultStructuredQueryBoundary(properties),
            properties)
        .execute(request);
    return statement;
  }

  private static JdbcOperations jdbcReturning(
      final ResultSet resultSet, final PreparedStatement statement) throws Exception {
    final JdbcOperations jdbc = mock(JdbcOperations.class);
    final Connection connection = mock(Connection.class);
    when(connection.prepareStatement(any(String.class))).thenReturn(statement);
    when(jdbc.query(
            any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
        .thenAnswer(
            invocation -> {
              final PreparedStatementCreator creator =
                  invocation.getArgument(0, PreparedStatementCreator.class);
              creator.createPreparedStatement(connection);
              final ResultSetExtractor<Object> extractor =
                  invocation.getArgument(1, ResultSetExtractor.class);
              return extractor.extractData(resultSet);
            });
    return jdbc;
  }

  private static ResultSet resultSet(final String[] labels, final Object[][] rows)
      throws Exception {
    final ResultSet resultSet = mock(ResultSet.class);
    final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(labels.length);
    for (int index = 0; index < labels.length; index++) {
      when(metadata.getColumnLabel(index + 1)).thenReturn(labels[index]);
    }
    final int[] nextIndex = {0};
    when(resultSet.next()).thenAnswer(invocation -> nextIndex[0]++ < rows.length);
    for (int column = 0; column < labels.length; column++) {
      final int resultColumn = column + 1;
      final int sourceColumn = column;
      final String label = labels[column].toLowerCase(java.util.Locale.ROOT);
      if (label.equals("count")
          || label.equals("patient_count")
          || label.equals("aggregate_count")) {
        final long[] values =
            java.util.Arrays.stream(rows)
                .mapToLong(row -> ((Number) row[sourceColumn]).longValue())
                .toArray();
        final int[] valueIndex = {0};
        when(resultSet.getLong(resultColumn))
            .thenAnswer(invocation -> values[Math.min(valueIndex[0]++, values.length - 1)]);
      } else {
        final String[] values =
            java.util.Arrays.stream(rows)
                .map(row -> String.valueOf(row[sourceColumn]))
                .toArray(String[]::new);
        final int[] valueIndex = {0};
        when(resultSet.getString(resultColumn))
            .thenAnswer(invocation -> values[Math.min(valueIndex[0]++, values.length - 1)]);
      }
    }
    return resultSet;
  }
}
