package com.medassist.clinicaldata.query;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.domain.Role;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates model-produced SQL before it can reach a structured-data adapter. */
@Component
public final class DefaultStructuredQueryBoundary implements StructuredQueryBoundary {
  private static final Pattern FROM_VIEW =
      Pattern.compile("\\bfrom\\s+([a-z_][a-z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern LIMIT =
      Pattern.compile("\\blimit\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
  private static final Set<String> FORBIDDEN_KEYWORDS =
      Set.of(
          "insert",
          "update",
          "delete",
          "drop",
          "alter",
          "truncate",
          "create",
          "grant",
          "revoke",
          "call",
          "copy",
          "execute",
          "merge",
          "into");
  private static final Set<String> FORBIDDEN_FUNCTIONS =
      Set.of(
          "pg_read_file",
          "pg_ls_dir",
          "lo_import",
          "dblink",
          "current_setting",
          "set_config",
          "pg_sleep");

  private final ClinicalQueryProperties properties;

  public DefaultStructuredQueryBoundary(final ClinicalQueryProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public void validate(final StructuredQueryRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.role() == Role.ADMIN) {
      throw new StructuredQueryAccessDeniedException("ADMIN cannot execute structured queries");
    }
    final String sql = request.sql().trim();
    final String normalized = sql.toLowerCase(Locale.ROOT);
    if (!normalized.startsWith("select ") && !normalized.equals("select")) {
      throw new StructuredQueryException("only SELECT statements are allowed");
    }
    if (sql.indexOf(';') >= 0
        || normalized.contains("--")
        || normalized.contains("/*")
        || normalized.contains("*/")
        || sql.indexOf('\u0000') >= 0) {
      throw new StructuredQueryException("comments and multiple SQL statements are not allowed");
    }
    for (final String keyword : FORBIDDEN_KEYWORDS) {
      if (containsWord(normalized, keyword)) {
        throw new StructuredQueryException("forbidden SQL operation");
      }
    }
    for (final String function : FORBIDDEN_FUNCTIONS) {
      if (Pattern.compile("\\b" + Pattern.quote(function) + "\\s*\\(").matcher(normalized).find()) {
        throw new StructuredQueryException("forbidden SQL function");
      }
    }
    final Matcher from = FROM_VIEW.matcher(normalized);
    if (!from.find() || !properties.allowedViews().contains(from.group(1))) {
      throw new StructuredQueryException("query must use an allow-listed research view");
    }
    if (countWord(normalized, "select") != 1
        || countWord(normalized, "from") != 1
        || containsWord(normalized, "union")
        || containsWord(normalized, "join")
        || fromClauseContainsMultipleRelations(normalized, from)) {
      throw new StructuredQueryException("query shape is not allow-listed");
    }
    if (!from.group(1).equals(request.view().sqlName())) {
      throw new StructuredQueryException("query view does not match the requested view");
    }
    final Matcher limit = LIMIT.matcher(normalized);
    if (!limit.find() || Integer.parseInt(limit.group(1)) <= 0) {
      throw new StructuredQueryException("a positive LIMIT is required");
    }
    if (Integer.parseInt(limit.group(1)) > properties.maxRows()) {
      throw new StructuredQueryException("query LIMIT exceeds configured maximum");
    }
    if (request.role() == Role.RESEARCHER && !isAggregate(normalized)) {
      throw new StructuredQueryException("RESEARCHER queries must be aggregate-only");
    }
    if (request.clinicalExemption() && request.role() != Role.CLINICIAN) {
      throw new StructuredQueryAccessDeniedException("clinical exemption is limited to CLINICIAN");
    }
  }

  private static boolean isAggregate(final String sql) {
    return sql.matches("(?s).*\\b(count|sum|avg|min|max)\\s*\\(.*");
  }

  private static boolean containsWord(final String text, final String word) {
    return text.matches("(?s).*\\b" + Pattern.quote(word) + "\\b.*");
  }

  private static int countWord(final String text, final String word) {
    final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static boolean fromClauseContainsMultipleRelations(
      final String normalized, final Matcher from) {
    final String fromClause = normalized.substring(from.end());
    final int clauseEnd = firstClauseBoundary(fromClause);
    return fromClause.substring(0, clauseEnd).contains(",");
  }

  private static int firstClauseBoundary(final String fromClause) {
    int boundary = fromClause.length();
    for (final String keyword :
        Set.of(" where ", " group ", " having ", " order ", " limit ", " offset ")) {
      final int index = fromClause.indexOf(keyword);
      if (index >= 0) {
        boundary = Math.min(boundary, index);
      }
    }
    return boundary;
  }
}
