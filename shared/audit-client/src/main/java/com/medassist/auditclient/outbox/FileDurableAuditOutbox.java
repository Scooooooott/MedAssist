package com.medassist.auditclient.outbox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/** Single-process, bounded FIFO outbox backed by atomically published binary files. */
public final class FileDurableAuditOutbox implements AuditOutbox {
  private static final int MAGIC = 0x4d414f42;
  private static final int FORMAT_VERSION = 1;
  private static final String SUFFIX = ".audit.pb";

  private final Path directory;
  private final int capacity;
  private final int maxMessageBytes;
  private final ReentrantLock lock = new ReentrantLock();
  private final Deque<StoredMessage> messages = new ArrayDeque<>();
  private final AtomicLong sequence = new AtomicLong();

  public FileDurableAuditOutbox(
      final Path directory, final int capacity, final int maxMessageBytes) {
    if (directory == null) {
      throw new IllegalArgumentException("audit outbox directory is required");
    }
    if (capacity <= 0 || maxMessageBytes <= 0) {
      throw new IllegalArgumentException("audit outbox limits must be positive");
    }
    this.directory = directory.toAbsolutePath().normalize();
    this.capacity = capacity;
    this.maxMessageBytes = maxMessageBytes;
    loadExistingMessages();
  }

  @Override
  public void append(final AuditOutboxMessage message) {
    requireWithinByteLimit(message);
    lock.lock();
    try {
      if (messages.size() >= capacity) {
        throw new AuditOutboxFullException("audit outbox is full");
      }
      final long nextSequence = sequence.incrementAndGet();
      final String fileName = "%020d-%s%s".formatted(nextSequence, message.eventId(), SUFFIX);
      final Path target = resolveInsideDirectory(fileName);
      final Path temporary = resolveInsideDirectory(fileName + ".tmp");
      try {
        writeMessage(temporary, message);
        moveAtomically(temporary, target);
      } catch (final IOException exception) {
        deleteQuietly(temporary);
        throw new IllegalStateException("failed to persist audit outbox message", exception);
      }
      messages.addLast(new StoredMessage(target, message));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<AuditOutboxMessage> peek() {
    lock.lock();
    try {
      return Optional.ofNullable(messages.peekFirst()).map(StoredMessage::message);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void acknowledge(final AuditOutboxMessage message) {
    lock.lock();
    try {
      final StoredMessage head = messages.peekFirst();
      if (head == null || !head.message().equals(message)) {
        throw new IllegalStateException("only the audit outbox head can be acknowledged");
      }
      try {
        Files.delete(head.path());
      } catch (final IOException exception) {
        throw new IllegalStateException("failed to acknowledge audit outbox message", exception);
      }
      messages.removeFirst();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int size() {
    lock.lock();
    try {
      return messages.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int capacity() {
    return capacity;
  }

  private void loadExistingMessages() {
    try {
      Files.createDirectories(directory);
      try (Stream<Path> paths = Files.list(directory)) {
        paths
            .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(this::loadMessage);
      }
    } catch (final IOException exception) {
      throw new IllegalStateException("failed to initialize audit outbox", exception);
    }
    if (messages.size() > capacity) {
      throw new IllegalStateException("persisted audit outbox exceeds configured capacity");
    }
  }

  private void loadMessage(final Path path) {
    try {
      final AuditOutboxMessage message = readMessage(path);
      requireWithinByteLimit(message);
      messages.addLast(new StoredMessage(path, message));
      final String prefix = path.getFileName().toString().substring(0, 20);
      sequence.accumulateAndGet(Long.parseLong(prefix), Math::max);
    } catch (final IOException | NumberFormatException exception) {
      throw new IllegalStateException("invalid persisted audit outbox entry: " + path, exception);
    }
  }

  private void writeMessage(final Path path, final AuditOutboxMessage message) throws IOException {
    try (FileChannel channel =
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        DataOutputStream output =
            new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
      output.writeInt(MAGIC);
      output.writeInt(FORMAT_VERSION);
      output.writeLong(message.eventId().getMostSignificantBits());
      output.writeLong(message.eventId().getLeastSignificantBits());
      output.writeInt(message.parentTraceHeaders().size());
      for (final Map.Entry<String, String> header : message.parentTraceHeaders().entrySet()) {
        output.writeUTF(header.getKey());
        output.writeUTF(header.getValue());
      }
      final byte[] payload = message.payload();
      output.writeInt(payload.length);
      output.write(payload);
      output.flush();
      channel.force(true);
    }
  }

  private AuditOutboxMessage readMessage(final Path path) throws IOException {
    try (DataInputStream input =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
        throw new IOException("unsupported audit outbox file format");
      }
      final UUID eventId = new UUID(input.readLong(), input.readLong());
      final int headerCount = input.readInt();
      if (headerCount < 0 || headerCount > 2) {
        throw new IOException("invalid audit trace header count");
      }
      final Map<String, String> traceHeaders = new TreeMap<>();
      for (int index = 0; index < headerCount; index++) {
        traceHeaders.put(input.readUTF(), input.readUTF());
      }
      final int payloadLength = input.readInt();
      if (payloadLength <= 0 || payloadLength > maxMessageBytes) {
        throw new IOException("invalid audit outbox payload length");
      }
      final byte[] payload = input.readNBytes(payloadLength);
      if (payload.length != payloadLength || input.read() != -1) {
        throw new EOFException("truncated or trailing audit outbox data");
      }
      return new AuditOutboxMessage(eventId, payload, traceHeaders);
    }
  }

  private void requireWithinByteLimit(final AuditOutboxMessage message) {
    if (message == null) {
      throw new IllegalArgumentException("audit outbox message is required");
    }
    if (message.payloadSizeBytes() > maxMessageBytes) {
      throw new IllegalArgumentException("audit outbox message exceeds configured byte limit");
    }
  }

  private Path resolveInsideDirectory(final String fileName) {
    final Path path = directory.resolve(fileName).normalize();
    if (!directory.equals(path.getParent())) {
      throw new IllegalArgumentException("audit outbox path escapes configured directory");
    }
    return path;
  }

  private static void moveAtomically(final Path source, final Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException exception) {
      Files.move(source, target);
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException ignored) {
      // The persistence exception remains the actionable failure.
    }
  }

  private record StoredMessage(Path path, AuditOutboxMessage message) {}
}
