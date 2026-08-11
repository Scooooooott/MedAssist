package com.medassist.ingestion.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObjectDiscoveryServiceTest {
  @Test
  void classifiesNewChangedAndUnchangedObjectsInDeterministicOrder() throws Exception {
    final ObjectDescriptor changed = object("s3://bucket/changed", "changed");
    final ObjectDescriptor unchanged = object("s3://bucket/unchanged", "same");
    final ObjectDescriptor newObject = object("s3://bucket/new", "new");
    final Map<URI, String> previous =
        Map.of(changed.storageUri(), sha256("old"), unchanged.storageUri(), sha256("same"));
    final ObjectDiscoveryService service =
        service(List.of(unchanged, newObject, changed), previous);

    final List<ObjectDiscoveryResult> results = service.discover(false);

    assertEquals(
        List.of("s3://bucket/changed", "s3://bucket/new", "s3://bucket/unchanged"),
        results.stream().map(result -> result.object().storageUri().toString()).toList());
    assertEquals(DiscoveryClassification.CHANGED, results.get(0).classification());
    assertTrue(results.get(0).processRequired());
    assertEquals(DiscoveryClassification.NEW, results.get(1).classification());
    assertTrue(results.get(1).processRequired());
    assertEquals(DiscoveryClassification.UNCHANGED, results.get(2).classification());
    assertFalse(results.get(2).processRequired());
  }

  @Test
  void forceReprocessMarksUnchangedObjectForProcessingWithoutChangingClassification()
      throws Exception {
    final ObjectDescriptor object = object("s3://bucket/doc", "same");
    final ObjectDiscoveryResult result =
        service(List.of(object), Map.of(object.storageUri(), sha256("same"))).discover(true).get(0);

    assertEquals(DiscoveryClassification.UNCHANGED, result.classification());
    assertTrue(result.processRequired());
  }

  @Test
  void rejectsDuplicateUrisBeforeOpeningAnyStream() {
    final AtomicInteger opens = new AtomicInteger();
    final ObjectDescriptor first = object("s3://bucket/duplicate", "one", opens);
    final ObjectDescriptor second = object("s3://bucket/duplicate", "two", opens);
    final ObjectDiscoveryService service = service(List.of(first, second), Map.of());

    final DiscoveryPermanentException exception =
        assertThrows(DiscoveryPermanentException.class, () -> service.discover(false));

    assertTrue(exception.getMessage().contains("duplicate"));
    assertEquals(0, opens.get());
  }

  @Test
  void propagatesCatalogAndFingerprintFailures() {
    final DiscoveryTransientException catalogFailure =
        new DiscoveryTransientException("catalog unavailable");
    final ObjectStoreCatalog failingCatalog =
        () -> {
          throw catalogFailure;
        };
    final ObjectDiscoveryService catalogService =
        new ObjectDiscoveryService(
            failingCatalog, (source, uri) -> Optional.empty(), new Sha256Hasher());
    assertEquals(
        catalogFailure,
        assertThrows(DiscoveryTransientException.class, () -> catalogService.discover(false)));

    final DiscoveryPermanentException repositoryFailure =
        new DiscoveryPermanentException("invalid fingerprint row");
    final ObjectDescriptor object = object("s3://bucket/doc", "content");
    final ObjectDiscoveryService repositoryService =
        new ObjectDiscoveryService(
            () -> List.of(object),
            (source, uri) -> {
              throw repositoryFailure;
            },
            new Sha256Hasher());
    assertEquals(
        repositoryFailure,
        assertThrows(DiscoveryPermanentException.class, () -> repositoryService.discover(false)));
  }

  @Test
  void wrapsStreamReadFailureAsTransientAndClosesStream() {
    final TrackingInputStream stream =
        new TrackingInputStream("prefix".getBytes(StandardCharsets.UTF_8), true);
    final ObjectDescriptor object =
        new ObjectDescriptor(
            URI.create("s3://bucket/broken"), "source", "text/plain", 6, Map.of(), () -> stream);

    final DiscoveryTransientException exception =
        assertThrows(DiscoveryTransientException.class, () -> new Sha256Hasher().hash(object));

    assertInstanceOf(IOException.class, exception.getCause());
    assertTrue(stream.closed);
  }

  @Test
  void hashesThroughStreamAndClosesIt() throws Exception {
    final byte[] content = "streamed medical document".getBytes(StandardCharsets.UTF_8);
    final TrackingInputStream stream = new TrackingInputStream(content, false);
    final ObjectDescriptor object =
        new ObjectDescriptor(
            URI.create("s3://bucket/document"),
            "source",
            "text/plain",
            content.length,
            Map.of(),
            () -> stream);

    assertEquals(
        sha256(new String(content, StandardCharsets.UTF_8)), new Sha256Hasher().hash(object));
    assertTrue(stream.closed);
    assertTrue(stream.bufferedReadCount > 0);
  }

  private static ObjectDiscoveryService service(
      final List<ObjectDescriptor> objects, final Map<URI, String> previous) {
    return new ObjectDiscoveryService(
        () -> objects, (source, uri) -> Optional.ofNullable(previous.get(uri)), new Sha256Hasher());
  }

  private static ObjectDescriptor object(final String uri, final String content) {
    return object(uri, content, new AtomicInteger());
  }

  private static ObjectDescriptor object(
      final String uri, final String content, final AtomicInteger opens) {
    final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new ObjectDescriptor(
        URI.create(uri),
        "source",
        "text/plain",
        bytes.length,
        Map.of("etag", "test"),
        () -> {
          opens.incrementAndGet();
          return new ByteArrayInputStream(bytes);
        });
  }

  private static String sha256(final String content) throws Exception {
    final byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
    final StringBuilder result = new StringBuilder();
    for (final byte value : digest) {
      result.append(String.format("%02x", value));
    }
    return result.toString();
  }

  private static final class TrackingInputStream extends InputStream {
    private final byte[] content;
    private final boolean failAtEof;
    private int position;
    private boolean closed;
    private int bufferedReadCount;

    private TrackingInputStream(final byte[] content, final boolean failAtEof) {
      this.content = content;
      this.failAtEof = failAtEof;
    }

    @Override
    public int read() throws IOException {
      if (closed) {
        throw new IOException("stream is closed");
      }
      if (position == content.length && failAtEof) {
        throw new IOException("synthetic read failure");
      }
      if (position == content.length) {
        return -1;
      }
      return content[position++];
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      bufferedReadCount++;
      if (closed) {
        throw new IOException("stream is closed");
      }
      if (position == content.length && failAtEof) {
        throw new IOException("synthetic read failure");
      }
      if (position == content.length) {
        return -1;
      }
      final int count = Math.min(length, content.length - position);
      System.arraycopy(content, position, buffer, offset, count);
      position += count;
      return count;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
