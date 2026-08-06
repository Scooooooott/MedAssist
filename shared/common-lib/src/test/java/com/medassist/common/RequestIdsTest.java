package com.medassist.common;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class RequestIdsTest {
  @Test
  void createReturnsDistinctIdentifiers() {
    final RequestIds first = RequestIds.create();
    final RequestIds second = RequestIds.create();

    assertNotNull(first.traceId());
    assertNotEquals(first.requestId(), second.requestId());
  }
}
