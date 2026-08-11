package com.medassist.ingestion.versioning;

/** Metadata fields required before a document version can enter the active chain. */
public enum VersionMetadataField {
  PUBLISHER("publisher"),
  VERSION("version"),
  EFFECTIVE_DATE("effective_date");

  private final String metadataKey;

  VersionMetadataField(final String metadataKey) {
    this.metadataKey = metadataKey;
  }

  public String metadataKey() {
    return metadataKey;
  }
}
