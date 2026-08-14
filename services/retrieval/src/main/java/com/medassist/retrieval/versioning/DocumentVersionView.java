package com.medassist.retrieval.versioning;

import java.time.LocalDate;
import java.util.UUID;

public record DocumentVersionView(
    UUID id,
    UUID documentId,
    String version,
    LocalDate effectiveDate,
    String status,
    UUID supersededBy,
    String publisher,
    Boolean stale) {}
