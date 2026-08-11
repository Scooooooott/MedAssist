package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.clinicaldata.query.DefaultStructuredQueryBoundary;
import com.medassist.clinicaldata.query.JdbcStructuredQueryExecutor;
import com.medassist.clinicaldata.query.StructuredQueryRequest;
import com.medassist.clinicaldata.query.StructuredView;
import com.medassist.domain.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

class JdbcStructuredQueryExecutorTest {
  @Test
  void suppressesGroupsBelowKAnonymity() throws Exception {
    final JdbcOperations jdbc = mock(JdbcOperations.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement statement = mock(PreparedStatement.class);
    final StructuredQueryRequest request = validRequest();
    when(connection.prepareStatement(request.sql())).thenReturn(statement);
    when(jdbc.query(
            org.mockito.ArgumentMatchers.any(PreparedStatementCreator.class),
            (ResultSetExtractor<Object>)
                org.mockito.ArgumentMatchers.any(ResultSetExtractor.class)))
        .thenAnswer(
            invocation -> {
              final PreparedStatementCreator creator =
                  invocation.getArgument(0, PreparedStatementCreator.class);
              creator.createPreparedStatement(connection);
              @SuppressWarnings("unchecked")
              final org.springframework.jdbc.core.ResultSetExtractor<Object> extractor =
                  invocation.getArgument(1, ResultSetExtractor.class);
              return extractor.extractData(resultSet(3L));
            });
    final ClinicalQueryProperties properties = ClinicalQueryProperties.defaults();

    final var result =
        new JdbcStructuredQueryExecutor(
                jdbc, new DefaultStructuredQueryBoundary(properties), properties)
            .execute(request);

    assertThat(result.rows()).isEmpty();
    assertThat(result.truncated()).isTrue();
    assertThat(result.kAnonymityExempt()).isFalse();
    verify(statement).setQueryTimeout(5);
  }

  @Test
  void roundsStatementTimeoutUpToWholeSeconds() throws Exception {
    final PreparedStatement statement = executeWithTimeout(1_001L);

    verify(statement).setQueryTimeout(2);
  }

  @Test
  void enforcesMinimumOneSecondStatementTimeout() throws Exception {
    final PreparedStatement statement = executeWithTimeout(1L);

    verify(statement).setQueryTimeout(1);
  }

  private static PreparedStatement executeWithTimeout(final long statementTimeoutMs)
      throws Exception {
    final JdbcOperations jdbc = mock(JdbcOperations.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement statement = mock(PreparedStatement.class);
    final ClinicalQueryProperties properties =
        new ClinicalQueryProperties(
            5, statementTimeoutMs, 100, true, ClinicalQueryProperties.defaults().allowedViews());
    final StructuredQueryRequest request = validRequest();
    when(connection.prepareStatement(request.sql())).thenReturn(statement);
    when(jdbc.query(
            org.mockito.ArgumentMatchers.any(PreparedStatementCreator.class),
            (ResultSetExtractor<Object>)
                org.mockito.ArgumentMatchers.any(ResultSetExtractor.class)))
        .thenAnswer(
            invocation -> {
              final PreparedStatementCreator creator =
                  invocation.getArgument(0, PreparedStatementCreator.class);
              creator.createPreparedStatement(connection);
              @SuppressWarnings("unchecked")
              final org.springframework.jdbc.core.ResultSetExtractor<Object> extractor =
                  invocation.getArgument(1, ResultSetExtractor.class);
              return extractor.extractData(resultSet(5L));
            });

    new JdbcStructuredQueryExecutor(
            jdbc, new DefaultStructuredQueryBoundary(properties), properties)
        .execute(request);
    return statement;
  }

  private static StructuredQueryRequest validRequest() {
    return new StructuredQueryRequest(
        "actor",
        Role.RESEARCHER,
        StructuredView.CONDITION_COUNTS,
        "select condition_code, count(*) from clinical_research_condition_counts limit 20",
        false,
        null);
  }

  private static ResultSet resultSet(final long count) throws Exception {
    final ResultSet resultSet = mock(ResultSet.class);
    final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(2);
    when(metadata.getColumnLabel(1)).thenReturn("condition_code");
    when(metadata.getColumnLabel(2)).thenReturn("count");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString(1)).thenReturn("C01");
    when(resultSet.getLong(2)).thenReturn(count);
    return resultSet;
  }
}
