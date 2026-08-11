package com.medassist.agent.security;

public enum SqlViolation {
  NONE("allowed"),
  INVALID_INPUT("SQL input is missing or too large"),
  MALFORMED_SQL("SQL cannot be safely tokenized"),
  MULTI_STATEMENT("multiple SQL statements are not allowed"),
  COMMENT("SQL comments are not allowed"),
  NOT_SELECT("only SELECT statements are allowed"),
  WRITE_OPERATION("DDL and DML operations are not allowed"),
  UNSAFE_KEYWORD("unsafe SQL keyword is not allowed"),
  DANGEROUS_FUNCTION("dangerous SQL function is not allowed"),
  SUBQUERY_NOT_ALLOWED("subqueries are not allowed"),
  NON_WHITELISTED_RELATION("relation is not on the allowlist"),
  MISSING_LIMIT("a bounded LIMIT is required"),
  INVALID_LIMIT("LIMIT must be a positive integer"),
  LIMIT_TOO_LARGE("LIMIT exceeds the configured maximum");

  private final String message;

  SqlViolation(final String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
