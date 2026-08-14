package com.medassist.auditgovernance.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/** Bounded FIFO buffer that persists each metadata-only event with an atomic file move. */
public final class FileDurableAuditEventBuffer implements DurableAuditEventBuffer {
  private static final String SUFFIX = ".audit.pb.json";

  private final Path directory;
  private final int capacity;
  private final int maxMessageBytes;
  private final ObjectMapper objectMapper;
  private final ReentrantLock lock = new ReentrantLock();
  private final Deque<StoredMessage> messages = new ArrayDeque<>();
  private final AtomicLong sequence = new AtomicLong();

  public FileDurableAuditEventBuffer(
      final Path directory,
      final int capacity,
      final int maxMessageBytes,
      final ObjectMapper objectMapper) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    if (capacity <= 0 || maxMessageBytes <= 0) {
      throw new IllegalArgumentException("buffer limits must be positive");
    }
    this.capacity = capacity;
    this.maxMessageBytes = maxMessageBytes;
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    loadExistingMessages();
  }

  @Override
  public boolean offer(final BufferedAuditMessage message) {
    Objects.requireNonNull(message, "message");
    if (message.sizeBytes() > maxMessageBytes) {
      throw new IllegalArgumentException("audit buffer message exceeds configured byte limit");
    }
    lock.lock();
    try {
      if (messages.size() >= capacity) {
        return false;
      }
      final long next = sequence.incrementAndGet();
      final String fileName = "%020d-%s%s".formatted(next, message.eventId(), SUFFIX);
      final Path target = resolveInsideDirectory(fileName);
      final Path temporary = resolveInsideDirectory(fileName + ".tmp");
      try {
        objectMapper.writeValue(temporary.toFile(), message);
        moveAtomically(temporary, target);
      } catch (final IOException exception) {
        deleteQuietly(temporary);
        throw new IllegalStateException("failed to persist audit buffer message", exception);
      }
      messages.addLast(new StoredMessage(target, message));
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<BufferedAuditMessage> peek() {
    lock.lock();
    try {
      return Optional.ofNullable(messages.peekFirst()).map(StoredMessage::message);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void acknowledge(final BufferedAuditMessage message) {
    lock.lock();
    try {
      final StoredMessage head = messages.peekFirst();
      if (head == null || !head.message().equals(message)) {
        throw new IllegalStateException("only the current audit buffer head can be acknowledged");
      }
      try {
        Files.delete(head.path());
      } catch (final IOException exception) {
        throw new IllegalStateException(
            "failed to delete acknowledged audit buffer message", exception);
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
      try (Stream<Path> files = Files.list(directory)) {
        files
            .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(this::loadMessage);
      }
    } catch (final IOException exception) {
      throw new IllegalStateException("failed to initialize durable audit buffer", exception);
    }
    if (messages.size() > capacity) {
      throw new IllegalStateException("persisted audit buffer exceeds configured capacity");
    }
  }

  private void loadMessage(final Path path) {
    try {
      final BufferedAuditMessage message =
          objectMapper.readValue(path.toFile(), BufferedAuditMessage.class);
      if (message.sizeBytes() > maxMessageBytes) {
        throw new IllegalStateException("persisted audit buffer message exceeds byte limit");
      }
      messages.addLast(new StoredMessage(path, message));
      final String prefix = path.getFileName().toString().substring(0, 20);
      sequence.accumulateAndGet(Long.parseLong(prefix), Math::max);
    } catch (final IOException | NumberFormatException exception) {
      throw new IllegalStateException("invalid persisted audit buffer entry: " + path, exception);
    }
  }

  private Path resolveInsideDirectory(final String fileName) {
    final Path resolved = directory.resolve(fileName).normalize();
    if (!resolved.getParent().equals(directory)) {
      throw new IllegalArgumentException("audit buffer path escapes configured directory");
    }
    return resolved;
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
      // The original persistence failure remains the actionable error.
    }
  }

  private record StoredMessage(Path path, BufferedAuditMessage message) {}
}
