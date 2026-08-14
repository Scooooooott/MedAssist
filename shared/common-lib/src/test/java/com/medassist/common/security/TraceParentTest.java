package com.medassist.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceParentTest {
  @Test
  void acceptsOnlyValidNonZeroW3cIdentifiers() {
    assertEquals(
        "4bf92f3577b34da6a3ce929d0e0e4736",
        TraceParent.traceId("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
            .orElseThrow());
    assertTrue(
        TraceParent.traceId("00-00000000000000000000000000000000-00f067aa0ba902b7-01").isEmpty());
    assertTrue(TraceParent.traceId("query text").isEmpty());
  }

  @Test
  void createsValidTraceParent() {
    assertTrue(TraceParent.traceId(TraceParent.create()).isPresent());
    assertEquals(
        "4bf92f3577b34da6a3ce929d0e0e4736",
        TraceParent.traceId(TraceParent.createForTraceId("4bf92f3577b34da6a3ce929d0e0e4736"))
            .orElseThrow());
  }
}
