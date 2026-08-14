package com.medassist.integration.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("fault-nightly")
class DegradationCodeConsistencyTest {
  private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

  @Test
  void fixtureUsesOneSafeCodeAcrossResponseTraceTrajectoryAndAudit() throws IOException {
    final List<String> rows = readRows();
    final Set<String> scenarios = new HashSet<>();

    assertEquals(6, rows.size());
    for (final String row : rows) {
      final String[] columns = row.split(",", -1);
      assertEquals(5, columns.length, () -> "invalid degradation fixture row: " + row);
      assertTrue(scenarios.add(columns[0]), () -> "duplicate scenario: " + columns[0]);
      assertFalse(columns[0].isBlank());
      assertTrue(SAFE_CODE.matcher(columns[1]).matches());
      assertEquals(columns[1], columns[2], () -> "trace code mismatch for " + columns[0]);
      assertEquals(columns[1], columns[3], () -> "trajectory code mismatch for " + columns[0]);
      assertEquals(columns[1], columns[4], () -> "audit code mismatch for " + columns[0]);
    }
  }

  private static List<String> readRows() throws IOException {
    try (var stream =
            DegradationCodeConsistencyTest.class
                .getClassLoader()
                .getResourceAsStream("faults/degradation-surfaces.csv");
        var reader =
            new BufferedReader(
                new InputStreamReader(
                    java.util.Objects.requireNonNull(stream), StandardCharsets.UTF_8))) {
      return reader.lines().skip(1).filter(line -> !line.isBlank()).toList();
    }
  }
}
