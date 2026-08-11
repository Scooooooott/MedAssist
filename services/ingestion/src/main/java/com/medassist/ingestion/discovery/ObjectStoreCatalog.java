package com.medassist.ingestion.discovery;

import java.util.List;

/** Port for listing source objects without coupling ingestion to a storage SDK. */
public interface ObjectStoreCatalog {
  List<ObjectDescriptor> listObjects() throws DiscoveryException;
}
