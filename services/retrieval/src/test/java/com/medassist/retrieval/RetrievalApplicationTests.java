package com.medassist.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.retrieval.application.model.RetrievalMode;
import com.medassist.retrieval.cache.CacheAdminController;
import com.medassist.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-only-key",
      "medassist.retrieval.grpc.enabled=false",
      "medassist.retrieval.database.validate-vector-dimension=false"
    })
class RetrievalApplicationTests {
  @Autowired private ApplicationContext applicationContext;

  @Autowired private RetrievalProperties properties;

  @Test
  void defaultContextUsesHybridAndDoesNotRegisterCacheAdminController() {
    assertThat(properties.getDefaultRetrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    assertThat(applicationContext.getBeansOfType(CacheAdminController.class)).isEmpty();
  }
}
