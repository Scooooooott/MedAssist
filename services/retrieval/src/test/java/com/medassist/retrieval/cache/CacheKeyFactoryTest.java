package com.medassist.retrieval.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.RetrievalFiltersDto;
import com.medassist.retrieval.application.model.RetrievalMode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheKeyFactoryTest {
  private final QueryNormalizer normalizer = new QueryNormalizer();
  private final CacheKeyFactory keys = new CacheKeyFactory(normalizer);

  @Test
  void normalizesCaseWhitespaceAndPunctuation() {
    assertEquals("t2dm aspirin", normalizer.normalize("  T2DM,   ASPIRIN! "));
  }

  @Test
  void embeddingKeyIncludesModelIdentity() {
    final String first = keys.embeddingKey("p:", "Aspirin?", "bge-m3", "v1");
    final String equivalent = keys.embeddingKey("p:", " aspirin ", "bge-m3", "v1");
    final String changedVersion = keys.embeddingKey("p:", "aspirin", "bge-m3", "v2");

    assertEquals(first, equivalent);
    assertNotEquals(first, changedVersion);
  }

  @Test
  void answerKeyIsOrderIndependentButRoleIsolated() {
    final RetrievalFiltersDto firstFilters =
        new RetrievalFiltersDto(
            Set.of("POLICY", "GUIDELINE"), Set.of("FDA", "CDC"), null, null, Set.of());
    final RetrievalFiltersDto secondFilters =
        new RetrievalFiltersDto(
            Set.of("GUIDELINE", "POLICY"), Set.of("CDC", "FDA"), null, null, Set.of());
    final AnswerRequest clinician = request("CLINICIAN", firstFilters);
    final AnswerRequest same = request("CLINICIAN", secondFilters);
    final AnswerRequest admin = request("ADMIN", firstFilters);

    assertEquals(keys.answerKey("p:", clinician), keys.answerKey("p:", same));
    assertNotEquals(keys.answerKey("p:", clinician), keys.answerKey("p:", admin));
  }

  @Test
  void answerKeySeparatesRetrievalShapeDimensions() {
    final RetrievalFiltersDto filters =
        new RetrievalFiltersDto(Set.of("GUIDELINE"), Set.of("CDC"), null, null, Set.of());
    final AnswerRequest baseline = request("CLINICIAN", filters);
    final AnswerRequest differentTopK =
        new AnswerRequest(
            "aspirin",
            5,
            filters,
            "CLINICIAN",
            "bge-m3",
            "v1",
            RetrievalMode.HYBRID,
            true,
            false,
            null,
            "structure-v1",
            50);
    assertNotEquals(keys.answerKey("p:", baseline), keys.answerKey("p:", differentTopK));
  }

  private AnswerRequest request(final String role, final RetrievalFiltersDto filters) {
    return new AnswerRequest(
        "aspirin",
        8,
        filters,
        role,
        "bge-m3",
        "v1",
        RetrievalMode.HYBRID,
        true,
        false,
        null,
        "structure-v1",
        50);
  }
}
