package com.medassist.ingestion.batch.audit;

import java.util.regex.Pattern;

final class SafeAuditValues {
  private static final Pattern SOURCE_SCOPE = Pattern.compile("[A-Za-z0-9._:/-]{1,128}");

  private SafeAuditValues() {}

  static String requireSourceScope(final String value) {
    if (value == null || !SOURCE_SCOPE.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid audit source scope");
    }
    return value;
  }
}
