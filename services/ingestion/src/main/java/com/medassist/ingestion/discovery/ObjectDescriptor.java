package com.medassist.ingestion.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Immutable metadata and stream access for one object-store object. */
public record ObjectDescriptor(
    URI storageUri,
    String sourceId,
    String mimeType,
    long size,
    Map<String, String> metadata,
    ObjectStreamSupplier streamSupplier) {

  public ObjectDescriptor {
    storageUri = requireAbsoluteUri(storageUri);
    sourceId = requireText(sourceId, "sourceId");
    mimeType = requireText(mimeType, "mimeType");
    if (size < 0) {
      throw new IllegalArgumentException("size must be non-negative");
    }
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    streamSupplier = Objects.requireNonNull(streamSupplier, "streamSupplier must not be null");
  }

  public InputStream openStream() throws IOException {
    return Objects.requireNonNull(
        streamSupplier.open(), "streamSupplier returned a null InputStream");
  }

  private static URI requireAbsoluteUri(final URI uri) {
    Objects.requireNonNull(uri, "storageUri must not be null");
    if (!uri.isAbsolute()) {
      throw new IllegalArgumentException("storageUri must be absolute: " + uri);
    }
    return uri;
  }

  private static String requireText(final String value, final String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
