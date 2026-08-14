package com.medassist.retrieval.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.medassist.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

class CacheAdminControllerTest {
  @Test
  void rejectsMissingOrIncorrectInternalTokenWithoutClearingCaches() {
    final AnswerResponseCache answerCache = mock(AnswerResponseCache.class);
    final CachingQueryEmbeddingClient embeddingCache = mock(CachingQueryEmbeddingClient.class);
    final RetrievalProperties properties = properties("expected-token");
    final CacheAdminController controller =
        new CacheAdminController(answerCache, embeddingCache, properties);

    assertThat(controller.clear(null).getStatusCode().value()).isEqualTo(403);
    assertThat(controller.clear("wrong-token").getStatusCode().value()).isEqualTo(403);

    org.mockito.Mockito.verifyNoInteractions(answerCache, embeddingCache);
  }

  @Test
  void clearsBothCachesWithTheConfiguredInternalToken() {
    final AnswerResponseCache answerCache = mock(AnswerResponseCache.class);
    final CachingQueryEmbeddingClient embeddingCache = mock(CachingQueryEmbeddingClient.class);
    final CacheAdminController controller =
        new CacheAdminController(answerCache, embeddingCache, properties("expected-token"));

    assertThat(controller.clear("expected-token").getStatusCode().value()).isEqualTo(200);

    verify(answerCache).clear();
    verify(embeddingCache).clear();
  }

  @Test
  void enablingTheControllerWithoutATokenFailsClosed() {
    final RetrievalProperties properties = properties("");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new CacheAdminController(
                    mock(AnswerResponseCache.class),
                    mock(CachingQueryEmbeddingClient.class),
                    properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MEDASSIST_CACHE_ADMIN_TOKEN");
  }

  private static RetrievalProperties properties(final String token) {
    final RetrievalProperties properties = new RetrievalProperties();
    properties.getCache().setAdminToken(token);
    return properties;
  }
}
