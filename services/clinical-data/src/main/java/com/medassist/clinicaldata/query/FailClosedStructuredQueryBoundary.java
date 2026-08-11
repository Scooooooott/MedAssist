package com.medassist.clinicaldata.query;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.domain.Role;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative pre-parser guard. A real SQL parser can replace this implementation later. */
public final class FailClosedStructuredQueryBoundary implements StructuredQueryBoundary {
  private static final Pattern LIMIT = Pattern.compile("\\blimit\\s+(\\d+)\\b");
  private static final Pattern FORBIDDEN =
      Pattern.compile(
          "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|merge|call|copy|execute|pg_sleep)\\b");
  private final ClinicalQueryProperties properties;

  public FailClosedStructuredQueryBoundary(final ClinicalQueryProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public void validate(final StructuredQueryRequest request) {
    Objects.requireNonNull(request, "request");
    authorize(request);
    final String sql = request.sql().trim();
    final String normalized = sql.toLowerCase(Locale.ROOT);
    if (sql.contains(";")) {
      throw new StructuredQueryValidationException("multiple SQL statements are not allowed");
    }
    if (!normalized.startsWith("select ") && !normalized.equals("select")) {
      throw new StructuredQueryValidationException("only SELECT statements are allowed");
    }
    if (FORBIDDEN.matcher(normalized).find()) {
      throw new StructuredQueryValidationException("statement contains a forbidden operation");
    }
    if (count(normalized, "select") > 1) {
      throw new StructuredQueryValidationException(
          "subqueries are not allowed before SQL parser integration");
    }
    if (normalized.contains(" from ")
        && !normalized.contains(" from " + request.view().sqlName())) {
      throw new StructuredQueryValidationException(
          "query must target the selected allowlisted view");
    }
    if (!properties.allowedViews().contains(request.view().sqlName())) {
      throw new StructuredQueryValidationException(
          "selected view is not configured as allowlisted");
    }
    final Matcher limit = LIMIT.matcher(normalized);
    if (!limit.find()) {
      throw new StructuredQueryValidationException("LIMIT is required");
    }
    if (Integer.parseInt(limit.group(1)) > properties.maxRows()) {
      throw new StructuredQueryValidationException("LIMIT exceeds the configured row limit");
    }
  }

  private static void authorize(final StructuredQueryRequest request) {
    if (request.role() == Role.ADMIN) {
      throw new StructuredQueryAccessDeniedException(
          "ADMIN is not allowed to execute clinical queries");
    }
    if (request.role() == Role.RESEARCHER && request.clinicalExemption()) {
      throw new StructuredQueryAccessDeniedException("clinical exemption is limited to CLINICIAN");
    }
  }

  private static int count(final String value, final String token) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(token, offset)) >= 0) {
      count++;
      offset += token.length();
    }
    return count;
  }
}
