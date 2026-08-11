package com.medassist.ingestion.discovery;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentFingerprintRepository implements DocumentFingerprintRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcDocumentFingerprintRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> findFingerprint(final String sourceId, final URI storageUri)
      throws DiscoveryException {
    try {
      final List<String> values =
          jdbc.queryForList(
              """
              SELECT dv.content_hash
                FROM document d
                JOIN document_version dv ON dv.document_id = d.id
               WHERE d.source_system = 'minio'
                 AND d.source_uri = :sourceUri
               ORDER BY dv.retrieved_at DESC, dv.id DESC
               LIMIT 1
              """,
              Map.of("sourceUri", storageUri.toString()),
              String.class);
      return values.stream().findFirst();
    } catch (final RuntimeException exception) {
      throw new DiscoveryTransientException(
          "document fingerprint lookup failed for source id " + sourceId, exception);
    }
  }
}
