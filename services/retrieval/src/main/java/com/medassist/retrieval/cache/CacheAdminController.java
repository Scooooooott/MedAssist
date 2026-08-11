package com.medassist.retrieval.cache;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.medassist.retrieval.config.RetrievalProperties;

@RestController
@ConditionalOnProperty(
    prefix = "medassist.retrieval.cache",
    name = "admin-enabled",
    havingValue = "true")
@RequestMapping("/internal/cache")
public final class CacheAdminController {
  private final AnswerResponseCache answerCache;
  private final CachingQueryEmbeddingClient embeddingCache;
  private final String adminToken;

  public CacheAdminController(
      final AnswerResponseCache answerCache,
      final CachingQueryEmbeddingClient embeddingCache,
      final RetrievalProperties properties) {
    this.answerCache = answerCache;
    this.embeddingCache = embeddingCache;
    this.adminToken = requireAdminToken(properties.getCache().getAdminToken());
  }

  @DeleteMapping
  public ResponseEntity<Map<String, String>> clear(
      @RequestHeader(name = "X-MedAssist-Internal-Token", required = false)
          final String providedToken) {
    if (!java.security.MessageDigest.isEqual(
        adminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        (providedToken == null ? "" : providedToken)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      return ResponseEntity.status(403).body(Map.of("status", "forbidden"));
    }
    embeddingCache.clear();
    answerCache.clear();
    return ResponseEntity.ok(Map.of("status", "cleared"));
  }

  private static String requireAdminToken(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "MEDASSIST_CACHE_ADMIN_TOKEN is required when cache administration is enabled");
    }
    return value;
  }
}
