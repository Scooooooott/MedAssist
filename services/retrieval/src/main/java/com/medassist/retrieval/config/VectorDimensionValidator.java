package com.medassist.retrieval.config;

import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.model.QueryEmbeddingClient;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class VectorDimensionValidator implements ApplicationRunner {
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern VECTOR_TYPE = Pattern.compile("vector\\((\\d+)\\)");
  private final RetrievalProperties properties;
  private final NamedParameterJdbcTemplate jdbc;
  private final QueryEmbeddingClient embeddingClient;

  public VectorDimensionValidator(
      final RetrievalProperties properties,
      final NamedParameterJdbcTemplate jdbc,
      final QueryEmbeddingClient embeddingClient) {
    this.properties = properties;
    this.jdbc = jdbc;
    this.embeddingClient = embeddingClient;
  }

  @Override
  public void run(final ApplicationArguments arguments) {
    if (!properties.getDatabase().isValidateVectorDimension()) {
      return;
    }
    final int configured = properties.getDatabase().getVectorDimension();
    final int database = readDatabaseDimension();
    final QueryEmbedding probe =
        embeddingClient.embed(
            "dimension readiness probe",
            properties.getDefaultModelName(),
            properties.getDefaultModelVersion());
    final int model = probe.vector().size();
    if (configured != database || configured != model) {
      throw new IllegalStateException(
          "vector dimension mismatch: configured="
              + configured
              + ", database="
              + database
              + ", model="
              + model);
    }
  }

  private int readDatabaseDimension() {
    final String schema = properties.getDatabase().getSchema();
    if (!IDENTIFIER.matcher(schema).matches()) {
      throw new IllegalStateException("invalid database schema identifier");
    }
    final String type =
        jdbc.queryForObject(
            """
            SELECT format_type(attribute.atttypid, attribute.atttypmod)
              FROM pg_attribute attribute
             WHERE attribute.attrelid = to_regclass(:tableName)
               AND attribute.attname = 'embedding'
               AND NOT attribute.attisdropped
            """,
            Map.of("tableName", schema + ".chunk_embedding"),
            String.class);
    final Matcher matcher = VECTOR_TYPE.matcher(type == null ? "" : type);
    if (!matcher.matches()) {
      throw new IllegalStateException("chunk_embedding.embedding is not a dimensioned vector");
    }
    return Integer.parseInt(matcher.group(1));
  }
}
