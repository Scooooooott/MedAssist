package com.medassist.agent.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StructuredQueryGuardTest {
  private final StructuredQueryGuard guard =
      new StructuredQueryGuard(Set.of("clinical_aggregate_view"), 100);

  @Test
  void allowsOnlyBoundedSelectFromWhitelistedView() {
    final SqlValidationResult result =
        guard.validate(
            "SELECT diagnosis, COUNT(*) FROM clinical_aggregate_view GROUP BY diagnosis LIMIT 10");

    assertTrue(result.allowed());
    assertEquals(SqlViolation.NONE, result.violation());
  }

  @Test
  void rejectsNaturalLanguageMissingOrOversizedLimit() {
    final SqlValidationResult naturalLanguage = guard.validate("How many patients are there?");
    final SqlValidationResult missingLimit =
        guard.validate("SELECT COUNT(*) FROM clinical_aggregate_view");
    final SqlValidationResult oversizedLimit =
        guard.validate("SELECT COUNT(*) FROM clinical_aggregate_view LIMIT 101");

    assertEquals(SqlViolation.NOT_SELECT, naturalLanguage.violation());
    assertEquals(SqlViolation.MISSING_LIMIT, missingLimit.violation());
    assertEquals(SqlViolation.LIMIT_TOO_LARGE, oversizedLimit.violation());
  }

  @Test
  void rejectsMultiStatementCommentsWritesDangerousFunctionsAndUnknownRelations() {
    final String[] unsafeSql = {
      "SELECT * FROM clinical_aggregate_view LIMIT 1; DROP TABLE users",
      "SELECT * FROM clinical_aggregate_view -- exfiltrate\n LIMIT 1",
      "DELETE FROM clinical_aggregate_view LIMIT 1",
      "SELECT pg_read_file('/etc/passwd') FROM clinical_aggregate_view LIMIT 1",
      "SELECT * FROM patient_table LIMIT 1"
    };
    final SqlViolation[] expected = {
      SqlViolation.MULTI_STATEMENT,
      SqlViolation.COMMENT,
      SqlViolation.WRITE_OPERATION,
      SqlViolation.DANGEROUS_FUNCTION,
      SqlViolation.NON_WHITELISTED_RELATION
    };

    for (int index = 0; index < unsafeSql.length; index++) {
      final SqlValidationResult result = guard.validate(unsafeSql[index]);
      assertFalse(result.allowed(), unsafeSql[index]);
      assertEquals(expected[index], result.violation(), unsafeSql[index]);
    }
  }

  @Test
  void allowsTrailingSemicolonButStillReturnsOnlyHashForAudit() {
    final String sql = "SELECT COUNT(*) FROM clinical_aggregate_view LIMIT 5;";

    final SqlValidationResult result = guard.validate(sql);

    assertTrue(result.allowed());
    assertEquals(64, result.sqlHash().length());
    assertFalse(result.toString().contains(sql));
  }
}
