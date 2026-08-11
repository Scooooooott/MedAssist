package com.medassist.ingestion.discovery;

import java.io.IOException;
import java.io.InputStream;

/** Opens a fresh stream for one object-store object. */
@FunctionalInterface
public interface ObjectStreamSupplier {
  InputStream open() throws IOException;
}
