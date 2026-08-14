package com.medassist.auditgovernance.transport;

import com.medassist.auditgovernance.AuditChainAnchor;
import com.medassist.auditgovernance.AuditChainStore;
import com.medassist.auditgovernance.AuditChainVerificationResult;
import com.medassist.auditgovernance.AuditChainVerifier;
import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditEventCategory;
import com.medassist.auditgovernance.AuditPayload;
import com.medassist.auditgovernance.CanonicalAuditEventSerializer;
import com.medassist.auditgovernance.HashChainVerifier;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/** Durable binary audit chain with explicit framing, checksums, and startup verification. */
public final class FileAuditChainStore implements AuditChainStore, ProcessedAuditEventStore {
  private static final int FILE_MAGIC = 0x4D414335; // MAC5
  private static final int FILE_VERSION = 1;
  private static final int RECORD_MAGIC = 0x41554431; // AUD1
  private static final int FILE_HEADER_BYTES = Integer.BYTES * 2;
  private static final int RECORD_HEADER_BYTES = Integer.BYTES * 2;
  private static final int RECORD_TRAILER_BYTES = Integer.BYTES;

  private final ReentrantLock lock = new ReentrantLock();
  private final Path file;
  private final int maxRecordBytes;
  private final AuditChainVerifier verifier;
  private final List<AuditEvent> events = new ArrayList<>();
  private final Set<UUID> eventIds = new HashSet<>();
  private boolean failed;

  public FileAuditChainStore(
      final Path directory, final String fileName, final int maxRecordBytes) {
    this(directory, fileName, maxRecordBytes, new HashChainVerifier());
  }

  FileAuditChainStore(
      final Path directory,
      final String fileName,
      final int maxRecordBytes,
      final AuditChainVerifier verifier) {
    if (directory == null || fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("audit chain directory and file are required");
    }
    if (maxRecordBytes <= 0) {
      throw new IllegalArgumentException("audit chain max record bytes must be positive");
    }
    this.file = directory.toAbsolutePath().normalize().resolve(fileName).normalize();
    if (!this.file.getParent().equals(directory.toAbsolutePath().normalize())) {
      throw new IllegalArgumentException("audit chain file must be directly inside its directory");
    }
    this.maxRecordBytes = maxRecordBytes;
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    initialize();
  }

  @Override
  public AuditEvent publish(final AuditEvent event) {
    Objects.requireNonNull(event, "event");
    lock.lock();
    try {
      requireHealthy();
      requireDiskMatchesMemory();
      if (eventIds.contains(event.eventId())) {
        throw new IllegalStateException("audit event is already present in the chain");
      }
      final String previousHash = events.isEmpty() ? "" : events.get(events.size() - 1).hash();
      final AuditEvent chained = event.withPreviousHash(previousHash);
      final AuditEvent sealed = chained.withHash(CanonicalAuditEventSerializer.hash(chained));
      final byte[] payload = encode(sealed);
      if (payload.length > maxRecordBytes) {
        throw new IllegalArgumentException("audit chain record exceeds configured maximum");
      }
      appendFrame(payload);
      events.add(sealed);
      eventIds.add(sealed.eventId());
      return sealed;
    } catch (final IOException exception) {
      failed = true;
      throw new IllegalStateException("audit chain append could not be made durable", exception);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<AuditEvent> events() {
    lock.lock();
    try {
      requireHealthy();
      return List.copyOf(events);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public AuditChainVerificationResult verify() {
    lock.lock();
    try {
      requireHealthy();
      final List<AuditEvent> persisted = readAll();
      final AuditChainVerificationResult result = verifier.verify(persisted);
      if (!result.valid()) {
        failed = true;
        return result;
      }
      if (!persisted.equals(events)) {
        final long sequence = firstDifference(persisted, events) + 1L;
        final UUID eventId =
            sequence <= persisted.size() ? persisted.get((int) sequence - 1).eventId() : null;
        final AuditChainVerificationResult changed =
            AuditChainVerificationResult.broken(
                sequence,
                eventId,
                Math.min(persisted.size(), events.size()),
                "chain changed on disk");
        failed = true;
        return changed;
      }
      return result;
    } catch (final IOException | RuntimeException exception) {
      failed = true;
      throw new IllegalStateException("audit chain verification failed closed", exception);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String lastHash() {
    lock.lock();
    try {
      requireHealthy();
      return events.isEmpty() ? "" : events.get(events.size() - 1).hash();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void anchor(final AuditChainAnchor anchor) {
    Objects.requireNonNull(anchor, "anchor");
    lock.lock();
    try {
      requireHealthy();
      anchor.anchor(events.isEmpty() ? "" : events.get(events.size() - 1).hash(), events.size());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean contains(final UUID eventId) {
    lock.lock();
    try {
      requireHealthy();
      return eventIds.contains(eventId);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markProcessed(final UUID eventId) {
    if (!contains(eventId)) {
      throw new IllegalStateException("processed audit event is not durable in the chain");
    }
  }

  Path file() {
    return file;
  }

  private void initialize() {
    try {
      Files.createDirectories(file.getParent());
      if (Files.notExists(file)) {
        try (FileChannel channel =
            FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          final ByteBuffer header =
              ByteBuffer.allocate(FILE_HEADER_BYTES).putInt(FILE_MAGIC).putInt(FILE_VERSION);
          header.flip();
          writeFully(channel, header);
          channel.force(true);
        }
      }
      final List<AuditEvent> restored = readAll();
      final AuditChainVerificationResult result = verifier.verify(restored);
      if (!result.valid()) {
        throw new IllegalStateException("persisted audit hash chain is invalid");
      }
      for (final AuditEvent event : restored) {
        if (!eventIds.add(event.eventId())) {
          throw new IllegalStateException("persisted audit chain contains a duplicate event id");
        }
      }
      events.addAll(restored);
    } catch (final IOException | RuntimeException exception) {
      failed = true;
      throw new IllegalStateException("audit chain could not be recovered safely", exception);
    }
  }

  private void requireDiskMatchesMemory() throws IOException {
    try {
      final List<AuditEvent> persisted = readAll();
      final AuditChainVerificationResult result = verifier.verify(persisted);
      if (!result.valid() || !persisted.equals(events)) {
        failed = true;
        throw new IllegalStateException("audit chain changed or became invalid on disk");
      }
    } catch (final RuntimeException exception) {
      failed = true;
      if (exception.getMessage() != null
          && exception.getMessage().contains("changed or became invalid")) {
        throw exception;
      }
      throw new IllegalStateException("audit chain changed or became invalid on disk", exception);
    }
  }

  private List<AuditEvent> readAll() throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      if (channel.size() < FILE_HEADER_BYTES) {
        throw new IllegalStateException("audit chain file header is truncated");
      }
      final ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
      readFully(channel, header);
      header.flip();
      if (header.getInt() != FILE_MAGIC || header.getInt() != FILE_VERSION) {
        throw new IllegalStateException("audit chain file header is invalid");
      }
      final List<AuditEvent> restored = new ArrayList<>();
      while (channel.position() < channel.size()) {
        if (channel.size() - channel.position() < RECORD_HEADER_BYTES) {
          throw new IllegalStateException("audit chain record header is truncated");
        }
        final ByteBuffer recordHeader = ByteBuffer.allocate(RECORD_HEADER_BYTES);
        readFully(channel, recordHeader);
        recordHeader.flip();
        if (recordHeader.getInt() != RECORD_MAGIC) {
          throw new IllegalStateException("audit chain record marker is invalid");
        }
        final int length = recordHeader.getInt();
        if (length <= 0 || length > maxRecordBytes) {
          throw new IllegalStateException("audit chain record length is invalid");
        }
        if (channel.size() - channel.position() < (long) length + RECORD_TRAILER_BYTES) {
          throw new IllegalStateException("audit chain record is truncated");
        }
        final ByteBuffer payload = ByteBuffer.allocate(length);
        readFully(channel, payload);
        final byte[] bytes = payload.array();
        final ByteBuffer trailer = ByteBuffer.allocate(RECORD_TRAILER_BYTES);
        readFully(channel, trailer);
        trailer.flip();
        if (trailer.getInt() != checksum(bytes)) {
          throw new IllegalStateException("audit chain record checksum is invalid");
        }
        restored.add(decode(bytes));
      }
      return restored;
    }
  }

  private void appendFrame(final byte[] payload) throws IOException {
    final ByteBuffer frame =
        ByteBuffer.allocate(RECORD_HEADER_BYTES + payload.length + RECORD_TRAILER_BYTES)
            .putInt(RECORD_MAGIC)
            .putInt(payload.length)
            .put(payload)
            .putInt(checksum(payload));
    frame.flip();
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      writeFully(channel, frame);
      channel.force(true);
    }
  }

  private static byte[] encode(final AuditEvent event) {
    try {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeLong(event.eventId().getMostSignificantBits());
        output.writeLong(event.eventId().getLeastSignificantBits());
        output.writeLong(event.timestamp().getEpochSecond());
        output.writeInt(event.timestamp().getNano());
        writeString(output, event.actor());
        writeString(output, event.category().name());
        writeString(output, event.role());
        writeString(output, event.action());
        writeString(output, event.resourceType());
        writeString(output, event.resourceId());
        writeString(output, event.outcome());
        output.writeInt(event.payload().fields().size());
        for (final Map.Entry<String, String> entry :
            new TreeMap<>(event.payload().fields()).entrySet()) {
          writeString(output, entry.getKey());
          writeString(output, entry.getValue());
        }
        writeString(output, event.payloadHash());
        writeString(output, event.previousHash());
        writeString(output, event.hash());
      }
      return bytes.toByteArray();
    } catch (final IOException impossible) {
      throw new IllegalStateException("audit chain record encoding failed", impossible);
    }
  }

  private static AuditEvent decode(final byte[] payload) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      final UUID eventId = new UUID(input.readLong(), input.readLong());
      final Instant timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt());
      final String actor = readString(input);
      final AuditEventCategory category = AuditEventCategory.valueOf(readString(input));
      final String role = readString(input);
      final String action = readString(input);
      final String resourceType = readString(input);
      final String resourceId = readString(input);
      final String outcome = readString(input);
      final int metadataCount = input.readInt();
      if (metadataCount < 0 || metadataCount > 256) {
        throw new IllegalStateException("audit chain metadata count is invalid");
      }
      final Map<String, String> metadata = new TreeMap<>();
      for (int index = 0; index < metadataCount; index++) {
        if (metadata.put(readString(input), readString(input)) != null) {
          throw new IllegalStateException("audit chain metadata contains duplicate keys");
        }
      }
      final AuditEvent event =
          new AuditEvent(
              eventId,
              timestamp,
              actor,
              category,
              role,
              action,
              resourceType,
              resourceId,
              outcome,
              AuditPayload.of(metadata),
              readString(input),
              readString(input),
              readString(input));
      if (input.read() != -1) {
        throw new IllegalStateException("audit chain record has trailing data");
      }
      return event;
    } catch (final EOFException exception) {
      throw new IllegalStateException("audit chain record payload is truncated", exception);
    } catch (final IOException | RuntimeException exception) {
      throw new IllegalStateException("audit chain record payload is invalid", exception);
    }
  }

  private static void writeString(final DataOutputStream output, final String value)
      throws IOException {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(final DataInputStream input) throws IOException {
    final int length = input.readInt();
    if (length < 0 || length > 1_048_576) {
      throw new IllegalStateException("audit chain string length is invalid");
    }
    final byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      throw new EOFException("audit chain string is truncated");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static int checksum(final byte[] payload) {
    final CRC32C checksum = new CRC32C();
    checksum.update(payload, 0, payload.length);
    return (int) checksum.getValue();
  }

  private static void readFully(final FileChannel channel, final ByteBuffer target)
      throws IOException {
    while (target.hasRemaining()) {
      if (channel.read(target) < 0) {
        throw new EOFException("audit chain file ended unexpectedly");
      }
    }
  }

  private static void writeFully(final FileChannel channel, final ByteBuffer source)
      throws IOException {
    while (source.hasRemaining()) {
      channel.write(source);
    }
  }

  private static int firstDifference(
      final List<AuditEvent> persisted, final List<AuditEvent> expected) {
    final int common = Math.min(persisted.size(), expected.size());
    for (int index = 0; index < common; index++) {
      if (!persisted.get(index).equals(expected.get(index))) {
        return index;
      }
    }
    return common;
  }

  private void requireHealthy() {
    if (failed) {
      throw new IllegalStateException("audit chain store is in a failed-closed state");
    }
  }
}
