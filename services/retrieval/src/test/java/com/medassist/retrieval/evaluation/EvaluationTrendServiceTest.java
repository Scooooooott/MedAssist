package com.medassist.retrieval.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EvaluationTrendServiceTest {
  private final EvaluationTrendRepository repository =
      Mockito.mock(EvaluationTrendRepository.class);
  private final EvaluationTrendService service = new EvaluationTrendService(repository);

  @Test
  void normalizesFiltersAndUsesDefaultLimit() {
    service.find(" golden-v2 ", " bge-m3 ", null);

    verify(repository).find(new EvaluationTrendQuery("golden-v2", "bge-m3", 20));
  }

  @Test
  void rejectsUnsafeOrUnboundedLimits() {
    assertThrows(InvalidEvaluationQueryException.class, () -> service.find(null, null, 101));
    assertThrows(InvalidEvaluationQueryException.class, () -> service.find(null, null, 0));
    assertThrows(InvalidEvaluationQueryException.class, () -> service.find(" ", null, 1));
    verifyNoInteractions(repository);
  }

  @Test
  void allowsBoundedLimitAndReturnsRepositoryRows() {
    final EvaluationRunView row = Mockito.mock(EvaluationRunView.class);
    Mockito.when(repository.find(new EvaluationTrendQuery(null, "bge-m3", 100)))
        .thenReturn(List.of(row));

    assertEquals(List.of(row), service.find(null, "bge-m3", 100));
  }
}
