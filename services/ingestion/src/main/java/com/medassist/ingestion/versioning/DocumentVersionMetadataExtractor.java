package com.medassist.ingestion.versioning;

import com.medassist.domain.DocumentIR;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts only explicitly supplied, strictly formatted version metadata from parser IR. */
public final class DocumentVersionMetadataExtractor {
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  public VersionMetadataResult extract(final DocumentIR documentIr) {
    Objects.requireNonNull(documentIr, "documentIr");
    final Map<String, String> metadata = documentIr.metadata();
    final EnumSet<VersionMetadataField> missing = EnumSet.noneOf(VersionMetadataField.class);
    final EnumSet<VersionMetadataField> invalid = EnumSet.noneOf(VersionMetadataField.class);

    final String publisher = readRequired(metadata, VersionMetadataField.PUBLISHER, missing);
    final String version = readRequired(metadata, VersionMetadataField.VERSION, missing);
    final String effectiveDateValue =
        readRequired(metadata, VersionMetadataField.EFFECTIVE_DATE, missing);
    final LocalDate effectiveDate = parseDate(effectiveDateValue, invalid);

    final VersionMetadataStatus status =
        missing.isEmpty() && invalid.isEmpty()
            ? VersionMetadataStatus.CONFIRMED
            : VersionMetadataStatus.UNKNOWN;
    return new VersionMetadataResult(status, publisher, version, effectiveDate, missing, invalid);
  }

  private static String readRequired(
      final Map<String, String> metadata,
      final VersionMetadataField field,
      final Set<VersionMetadataField> missingFields) {
    final String value = metadata.get(field.metadataKey());
    if (value == null || value.trim().isEmpty()) {
      missingFields.add(field);
      return null;
    }
    return value.trim();
  }

  private static LocalDate parseDate(
      final String value, final Set<VersionMetadataField> invalidFields) {
    if (value == null) {
      return null;
    }
    if (!ISO_DATE.matcher(value).matches()) {
      invalidFields.add(VersionMetadataField.EFFECTIVE_DATE);
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (final DateTimeException exception) {
      invalidFields.add(VersionMetadataField.EFFECTIVE_DATE);
      return null;
    }
  }
}
