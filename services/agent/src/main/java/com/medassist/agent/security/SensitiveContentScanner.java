package com.medassist.agent.security;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveContentScanner {
  private static final Pattern PHI_MARKER =
      Pattern.compile("(?i)\\b(?:phi|protected health information|personal health information)\\b");
  private static final Pattern PATIENT_ID =
      Pattern.compile(
          "(?i)\\b(?:patient|pt|mrn|medical record|patient[-_ ]?(?:id|number|no))\\s*[:=#-]?\\s*[A-Z0-9][A-Z0-9-]{2,}\\b");
  private static final Pattern NAME =
      Pattern.compile(
          "(?i:\\b(?:patient|name|mr|mrs|ms)\\b)\\s*[:=]?\\s+\\b\\p{Lu}\\p{Ll}{2,}\\b|\\b\\p{Lu}\\p{Ll}{2,}\\s+\\p{Lu}\\p{Ll}{2,}\\b");
  private static final Pattern EXACT_DATE =
      Pattern.compile(
          "\\b(?:19|20)\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b|\\b\\d{1,2}[-/.]\\d{1,2}[-/.](?:19|20)?\\d{2}\\b|(?i)\\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2}(?:st|nd|rd|th)?[,]?\\s+(?:19|20)\\d{2}\\b");
  private static final Pattern FULL_ADDRESS =
      Pattern.compile(
          "(?i)\\b(?:full\\s+)?address\\s*[:=]\\s*[^,;\\n]{6,}|\\b\\d{1,6}\\s+\\p{L}[\\p{L}.'-]*(?:\\s+\\p{L}[\\p{L}.'-]*){0,5}\\s+(?:street|st|road|rd|avenue|ave|boulevard|blvd|lane|ln|drive|dr)\\b");
  private static final Pattern EMAIL =
      Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern PHONE =
      Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d ()-]{7,}\\d)(?!\\d)");
  private static final Pattern SOCIAL_SECURITY_NUMBER =
      Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)");

  private SensitiveContentScanner() {}

  public static Set<SensitiveFinding> find(final String payload) {
    final EnumSet<SensitiveFinding> findings = EnumSet.noneOf(SensitiveFinding.class);
    if (payload == null || payload.isBlank()) {
      return Set.copyOf(findings);
    }
    addIfFound(findings, SensitiveFinding.PHI_MARKER, PHI_MARKER, payload);
    addIfFound(findings, SensitiveFinding.PATIENT_ID, PATIENT_ID, payload);
    addIfFound(findings, SensitiveFinding.NAME, NAME, payload);
    addIfFound(findings, SensitiveFinding.EXACT_DATE, EXACT_DATE, payload);
    addIfFound(findings, SensitiveFinding.FULL_ADDRESS, FULL_ADDRESS, payload);
    addIfFound(findings, SensitiveFinding.EMAIL, EMAIL, payload);
    addIfFound(findings, SensitiveFinding.PHONE, PHONE, payload);
    addIfFound(findings, SensitiveFinding.SOCIAL_SECURITY_NUMBER, SOCIAL_SECURITY_NUMBER, payload);
    return Set.copyOf(findings);
  }

  private static void addIfFound(
      final Set<SensitiveFinding> findings,
      final SensitiveFinding finding,
      final Pattern pattern,
      final String payload) {
    if (pattern.matcher(payload).find()) {
      findings.add(finding);
    }
  }
}
