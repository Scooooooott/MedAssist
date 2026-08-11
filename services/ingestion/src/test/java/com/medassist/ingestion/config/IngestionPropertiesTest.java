package com.medassist.ingestion.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IngestionPropertiesTest {
  @Test
  void defaultsToSafeHarborSurrogate() {
    final IngestionProperties properties = new IngestionProperties();

    assertEquals("SAFE_HARBOR_SURROGATE", properties.getDeidentificationPolicy());
  }
}
