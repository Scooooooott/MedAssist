package com.medassist.ingestion.context.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@SuppressWarnings("unchecked")
class JdbcContextBackfillRepositoryTest {

  @Test
  void updatesOnlyContextColumnsAndRoutesModeSpecificVectorByDimension() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final UUID chunkId = UUID.randomUUID();
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(chunkId));
    final JdbcContextBackfillRepository repository = repository(jdbc);
    final List<Float> embedding =
        java.util.stream.IntStream.range(0, 768).mapToObj(value -> (float) value).toList();

    repository.save(
        new ContextBackfillWrite(
            ContextualRetrievalMode.RULE_BASED,
            "prompt-v1",
            new EmbeddingModel("model", "v1", 768),
            List.of(new ContextBackfillChunkWrite(chunkId, "Safe context", embedding))));

    final ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(updateSql.capture(), any(SqlParameterSource.class));
    assertThat(updateSql.getValue()).contains("SET context_prefix");
    assertThat(updateSql.getValue()).doesNotContain("SET text", "text =");
    final ArgumentCaptor<String> embeddingSql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).query(embeddingSql.capture(), parameters.capture(), any(RowMapper.class));
    assertThat(embeddingSql.getValue()).contains("chunk_embedding_768");
    assertThat(parameters.getValue().getValue("mode")).isEqualTo("RULE_BASED");
  }

  private static JdbcContextBackfillRepository repository(final NamedParameterJdbcTemplate jdbc) {
    final TransactionOperations transactions =
        new TransactionOperations() {
          @Override
          public <T> T execute(final TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
          }
        };
    return new JdbcContextBackfillRepository(jdbc, transactions);
  }
}
