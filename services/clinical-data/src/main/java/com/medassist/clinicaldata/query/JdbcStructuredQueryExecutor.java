package com.medassist.clinicaldata.query;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

/** JDBC adapter for allow-listed aggregate views; validation must run first. */
public final class JdbcStructuredQueryExecutor implements StructuredQueryExecutor {
  private final JdbcOperations jdbc;
  private final StructuredQueryBoundary boundary;
  private final ClinicalQueryProperties properties;

  public JdbcStructuredQueryExecutor(
      final JdbcOperations jdbc, final StructuredQueryBoundary boundary) {
    this(jdbc, boundary, ClinicalQueryProperties.defaults());
  }

  public JdbcStructuredQueryExecutor(
      final JdbcOperations jdbc,
      final StructuredQueryBoundary boundary,
      final ClinicalQueryProperties properties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.boundary = Objects.requireNonNull(boundary, "boundary");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public StructuredQueryResult execute(final StructuredQueryRequest request) {
    boundary.validate(request);
    final MappedRows mappedRows =
        jdbc.query(
            (PreparedStatementCreator)
                connection -> {
                  final PreparedStatement statement = connection.prepareStatement(request.sql());
                  statement.setQueryTimeout(queryTimeoutSeconds(properties.statementTimeoutMs()));
                  return statement;
                },
            (ResultSetExtractor<MappedRows>) resultSet -> mapRows(resultSet, request));
    return new StructuredQueryResult(
        request.view(),
        List.of(
            new StructuredResultColumn("dimensions", "allow-listed aggregate dimensions"),
            new StructuredResultColumn("count", "aggregate count")),
        mappedRows.rows(),
        mappedRows.suppressed(),
        request.clinicalExemption());
  }

  private static int queryTimeoutSeconds(final long statementTimeoutMs) {
    final long roundedSeconds = Math.max(1L, Math.ceilDiv(statementTimeoutMs, 1_000L));
    return (int) Math.min(roundedSeconds, Integer.MAX_VALUE);
  }

  private MappedRows mapRows(final ResultSet resultSet, final StructuredQueryRequest request)
      throws SQLException {
    final List<StructuredAggregateRow> rows = new ArrayList<>();
    boolean suppressed = false;
    final int columnCount = resultSet.getMetaData().getColumnCount();
    while (resultSet.next()) {
      final LinkedHashMap<String, String> dimensions = new LinkedHashMap<>();
      Long patientCount = null;
      Long fallbackCount = null;
      for (int index = 1; index <= columnCount; index++) {
        final String label = resultSet.getMetaData().getColumnLabel(index).toLowerCase(Locale.ROOT);
        if (label.equals("patient_count")) {
          patientCount = resultSet.getLong(index);
        } else if (label.equals("count") || label.equals("aggregate_count")) {
          fallbackCount = resultSet.getLong(index);
        } else if (!label.endsWith("_id") && !label.equals("id")) {
          dimensions.put(label, resultSet.getString(index));
        }
      }
      final long count =
          patientCount != null ? patientCount : fallbackCount == null ? -1 : fallbackCount;
      if (count < 0) {
        throw new StructuredQueryException("aggregate result must contain a count column");
      }
      if (!request.clinicalExemption() && count < properties.kAnonymity()) {
        suppressed = true;
      } else {
        rows.add(new StructuredAggregateRow(dimensions, count));
      }
    }
    return new MappedRows(List.copyOf(rows), suppressed);
  }

  private record MappedRows(List<StructuredAggregateRow> rows, boolean suppressed) {}
}
