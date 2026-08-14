package com.medassist.auditclient.autoconfigure;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the opt-in shared audit publisher. */
@ConfigurationProperties("medassist.audit.client")
public class AuditClientProperties {
  private boolean enabled;
  private String topic = "audit-events";
  private Path outboxDirectory = Path.of("./var/audit-outbox");
  private int outboxCapacity = 10_000;
  private int maxMessageBytes = 65_536;
  private Duration drainDelay = Duration.ofSeconds(5);
  private Duration sendTimeout = Duration.ofSeconds(5);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(final String topic) {
    this.topic = topic;
  }

  public Path getOutboxDirectory() {
    return outboxDirectory;
  }

  public void setOutboxDirectory(final Path outboxDirectory) {
    this.outboxDirectory = outboxDirectory;
  }

  public int getOutboxCapacity() {
    return outboxCapacity;
  }

  public void setOutboxCapacity(final int outboxCapacity) {
    this.outboxCapacity = outboxCapacity;
  }

  public int getMaxMessageBytes() {
    return maxMessageBytes;
  }

  public void setMaxMessageBytes(final int maxMessageBytes) {
    this.maxMessageBytes = maxMessageBytes;
  }

  public Duration getDrainDelay() {
    return drainDelay;
  }

  public void setDrainDelay(final Duration drainDelay) {
    this.drainDelay = drainDelay;
  }

  public Duration getSendTimeout() {
    return sendTimeout;
  }

  public void setSendTimeout(final Duration sendTimeout) {
    this.sendTimeout = sendTimeout;
  }

  public void validate() {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("medassist.audit.client.topic is required");
    }
    if (outboxDirectory == null) {
      throw new IllegalArgumentException("medassist.audit.client.outbox-directory is required");
    }
    if (outboxCapacity <= 0 || maxMessageBytes <= 0) {
      throw new IllegalArgumentException("audit outbox limits must be positive");
    }
    requirePositive(drainDelay, "drain-delay");
    requirePositive(sendTimeout, "send-timeout");
  }

  private static void requirePositive(final Duration value, final String property) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "medassist.audit.client." + property + " must be positive");
    }
  }
}
