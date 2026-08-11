package com.medassist.retrieval.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RetrievalPropertiesTest {
  @Test
  void usesM22DefaultTopKAndAllowsOverride() {
    final RetrievalProperties properties = new RetrievalProperties();

    assertEquals(8, properties.getDefaultTopK());

    properties.setDefaultTopK(12);

    assertEquals(12, properties.getDefaultTopK());
  }
}
