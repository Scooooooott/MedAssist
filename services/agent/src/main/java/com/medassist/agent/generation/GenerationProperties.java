package com.medassist.agent.generation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.generation")
public record GenerationProperties(
    String keyPrefix,
    String policyVersion,
    Duration maxDuration,
    Duration maxBackgroundWindow,
    int maxTokens,
    int maxEvents,
    long maxBufferedBytes,
    int maxActivePerUser,
    int maxActiveGlobal,
    Duration ttl,
    Duration expiredMetadataRetention,
    int maxChunkCharacters,
    int replayBatchSize,
    Duration replayPollInterval) {
  public GenerationProperties {
    keyPrefix = requireText(keyPrefix, "keyPrefix");
    policyVersion = requireText(policyVersion, "policyVersion");
    requirePositive(maxDuration, "maxDuration");
    requirePositive(maxBackgroundWindow, "maxBackgroundWindow");
    requirePositive(ttl, "ttl");
    requirePositive(expiredMetadataRetention, "expiredMetadataRetention");
    requirePositive(replayPollInterval, "replayPollInterval");
    if (maxDuration.compareTo(ttl) >= 0 || maxBackgroundWindow.compareTo(ttl) >= 0) {
      throw new IllegalArgumentException("generation execution windows must be shorter than ttl");
    }
    if (maxTokens <= 0
        || maxEvents < 3
        || maxBufferedBytes < 1024
        || maxActivePerUser <= 0
        || maxActiveGlobal < maxActivePerUser
        || maxChunkCharacters <= 0
        || replayBatchSize <= 0) {
      throw new IllegalArgumentException("generation limits are invalid");
    }
  }

  private static String requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static void requirePositive(final Duration value, final String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
