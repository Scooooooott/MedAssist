package com.medassist.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.retrieval.application.model.RetrievalMode;
import com.medassist.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-only-key",
      "medassist.retrieval.grpc.enabled=false",
      "medassist.retrieval.database.validate-vector-dimension=false"
    })
@ActiveProfiles("m1-baseline")
class M1BaselineProfileTest {
  @Autowired private RetrievalProperties properties;

  @Test
  void m1BaselineProfileDefaultsToVectorOnly() {
    assertThat(properties.getDefaultRetrievalMode()).isEqualTo(RetrievalMode.VECTOR_ONLY);
  }
}
