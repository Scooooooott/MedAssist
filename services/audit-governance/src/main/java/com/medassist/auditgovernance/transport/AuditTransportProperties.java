package com.medassist.auditgovernance.transport;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medassist.audit.transport")
public class AuditTransportProperties {
  public enum Mode {
    DIRECT,
    KAFKA
  }

  public enum BufferMode {
    FILE,
    IN_MEMORY
  }

  private Mode mode = Mode.DIRECT;
  private String topic = "audit-events";
  private String dlqTopic = "audit-events-dlq";
  private String consumerGroup = "audit-governance-v1";
  private boolean trajectoryEventsEnabled;
  private long retryAttempts = 2;
  private long retryDelayMs = 1_000;
  private final Buffer buffer = new Buffer();
  private final Chain chain = new Chain();

  public Mode getMode() {
    return mode;
  }

  public void setMode(final Mode mode) {
    this.mode = mode;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(final String topic) {
    this.topic = topic;
  }

  public String getDlqTopic() {
    return dlqTopic;
  }

  public void setDlqTopic(final String dlqTopic) {
    this.dlqTopic = dlqTopic;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public void setConsumerGroup(final String consumerGroup) {
    this.consumerGroup = consumerGroup;
  }

  public boolean isTrajectoryEventsEnabled() {
    return trajectoryEventsEnabled;
  }

  public void setTrajectoryEventsEnabled(final boolean trajectoryEventsEnabled) {
    this.trajectoryEventsEnabled = trajectoryEventsEnabled;
  }

  public long getRetryAttempts() {
    return retryAttempts;
  }

  public void setRetryAttempts(final long retryAttempts) {
    this.retryAttempts = retryAttempts;
  }

  public long getRetryDelayMs() {
    return retryDelayMs;
  }

  public void setRetryDelayMs(final long retryDelayMs) {
    this.retryDelayMs = retryDelayMs;
  }

  public Buffer getBuffer() {
    return buffer;
  }

  public Chain getChain() {
    return chain;
  }

  public void validate() {
    if (mode == null) {
      throw new IllegalArgumentException("audit transport mode is required");
    }
    requireText(topic, "topic");
    requireText(dlqTopic, "dlqTopic");
    requireText(consumerGroup, "consumerGroup");
    if (retryAttempts < 0 || retryDelayMs < 0) {
      throw new IllegalArgumentException("audit retry settings must not be negative");
    }
    if (trajectoryEventsEnabled) {
      throw new IllegalArgumentException("trajectory event transport is not approved for M5.2");
    }
    buffer.validate();
    chain.validate();
  }

  private static void requireText(final String value, final String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("audit transport " + field + " is required");
    }
  }

  public static class Buffer {
    private BufferMode mode = BufferMode.FILE;
    private int capacity = 10_000;
    private Path directory = Path.of("var", "audit-buffer");
    private int maxMessageBytes = 65_536;
    private long drainDelayMs = 5_000;
    private long sendTimeoutMs = 5_000;

    public BufferMode getMode() {
      return mode;
    }

    public void setMode(final BufferMode mode) {
      this.mode = mode;
    }

    public int getCapacity() {
      return capacity;
    }

    public void setCapacity(final int capacity) {
      this.capacity = capacity;
    }

    public Path getDirectory() {
      return directory;
    }

    public void setDirectory(final Path directory) {
      this.directory = directory;
    }

    public int getMaxMessageBytes() {
      return maxMessageBytes;
    }

    public void setMaxMessageBytes(final int maxMessageBytes) {
      this.maxMessageBytes = maxMessageBytes;
    }

    public long getDrainDelayMs() {
      return drainDelayMs;
    }

    public void setDrainDelayMs(final long drainDelayMs) {
      this.drainDelayMs = drainDelayMs;
    }

    public long getSendTimeoutMs() {
      return sendTimeoutMs;
    }

    public void setSendTimeoutMs(final long sendTimeoutMs) {
      this.sendTimeoutMs = sendTimeoutMs;
    }

    private void validate() {
      if (mode == null || directory == null) {
        throw new IllegalArgumentException("audit buffer mode and directory are required");
      }
      if (capacity <= 0 || maxMessageBytes <= 0 || drainDelayMs <= 0 || sendTimeoutMs <= 0) {
        throw new IllegalArgumentException("audit buffer limits must be positive");
      }
    }
  }

  public static class Chain {
    private Path directory = Path.of("var", "audit-chain");
    private String file = "audit-chain.bin";
    private int maxRecordBytes = 65_536;
    private long verificationIntervalMs = 60_000;

    public Path getDirectory() {
      return directory;
    }

    public void setDirectory(final Path directory) {
      this.directory = directory;
    }

    public String getFile() {
      return file;
    }

    public void setFile(final String file) {
      this.file = file;
    }

    public int getMaxRecordBytes() {
      return maxRecordBytes;
    }

    public void setMaxRecordBytes(final int maxRecordBytes) {
      this.maxRecordBytes = maxRecordBytes;
    }

    public long getVerificationIntervalMs() {
      return verificationIntervalMs;
    }

    public void setVerificationIntervalMs(final long verificationIntervalMs) {
      this.verificationIntervalMs = verificationIntervalMs;
    }

    private void validate() {
      if (directory == null || file == null || file.isBlank()) {
        throw new IllegalArgumentException("audit chain directory and file are required");
      }
      final Path filePath = Path.of(file);
      if (filePath.isAbsolute() || filePath.getNameCount() != 1 || ".".equals(file)) {
        throw new IllegalArgumentException("audit chain file must be a simple file name");
      }
      if (maxRecordBytes < 512 || maxRecordBytes > 16 * 1024 * 1024) {
        throw new IllegalArgumentException("audit chain max record bytes is outside safe limits");
      }
      if (verificationIntervalMs < 1_000) {
        throw new IllegalArgumentException("audit chain verification interval is too short");
      }
    }
  }
}
