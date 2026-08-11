package com.medassist.agent.security;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class PromptInjectionDetector {
  private static final Pattern INSTRUCTION_HIJACK =
      Pattern.compile(
          "\\b(?:ignore|disregard|forget|override|do not follow)\\b.{0,80}\\b(?:previous|prior|above|system|developer|all)\\b.{0,40}\\b(?:instruction|prompt|rule|message)s?\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  private static final Pattern DIRECTIVE_HIJACK =
      Pattern.compile(
          "\\b(?:you must|you should|do not|never|always)\\b.{0,100}\\b(?:ignore|reveal|follow|send|call|execute|delete|disclose|output)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  private static final Pattern PROMPT_EXFILTRATION =
      Pattern.compile(
          "\\b(?:reveal|show|print|output|display|leak|disclose|share)\\b.{0,80}\\b(?:system|hidden|original|developer|full|internal)?\\s*(?:prompt|instruction|secret|api\\s*key|token|credential)s?\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  private static final Pattern SAFETY_BYPASS =
      Pattern.compile(
          "\\b(?:bypass|disable|override|evade|circumvent|remove|turn\\s+off)\\b.{0,80}\\b(?:safety|security|guardrail|policy|restriction|filter|validation)s?\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  private static final Pattern ROLE_HIJACK =
      Pattern.compile(
          "\\b(?:you are now|act as|pretend to be|roleplay as|developer mode|unrestricted assistant|jailbreak)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern TOOL_ABUSE =
      Pattern.compile(
          "\\b(?:call|use|invoke|run|execute)\\b.{0,80}\\b(?:tool|sql|structured\\s+query|database|query)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  private static final Pattern BOUNDARY_SPOOFING =
      Pattern.compile(
          "(?:<\\s*/?\\s*(?:system|developer|assistant|instructions?)\\s*>|\\[\\s*(?:system|developer|assistant)\\s*\\]|#{2,}\\s*(?:system|developer|instructions?))",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern ENCODING_OBFUSCATION =
      Pattern.compile(
          "\\b(?:decode|decipher|decrypt|base64|rot13|hex)\\b.{0,80}\\b(?:instruction|prompt|command|secret)s?\\b|\\b[A-Za-z0-9+/]{96,}={0,2}\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

  public PromptInjectionResult detect(final String text) {
    if (text == null || text.isBlank()) {
      return new PromptInjectionResult(true, Set.of(PromptInjectionCategory.INVALID_INPUT));
    }
    final String normalized = normalize(text);
    final EnumSet<PromptInjectionCategory> categories =
        EnumSet.noneOf(PromptInjectionCategory.class);
    addIfMatched(
        categories, PromptInjectionCategory.INSTRUCTION_HIJACK, INSTRUCTION_HIJACK, normalized);
    addIfMatched(
        categories, PromptInjectionCategory.INSTRUCTION_HIJACK, DIRECTIVE_HIJACK, normalized);
    addIfMatched(
        categories, PromptInjectionCategory.PROMPT_EXFILTRATION, PROMPT_EXFILTRATION, normalized);
    addIfMatched(categories, PromptInjectionCategory.SAFETY_BYPASS, SAFETY_BYPASS, normalized);
    addIfMatched(categories, PromptInjectionCategory.ROLE_HIJACK, ROLE_HIJACK, normalized);
    addIfMatched(categories, PromptInjectionCategory.TOOL_ABUSE, TOOL_ABUSE, normalized);
    addIfMatched(
        categories, PromptInjectionCategory.BOUNDARY_SPOOFING, BOUNDARY_SPOOFING, normalized);
    addIfMatched(
        categories, PromptInjectionCategory.ENCODING_OBFUSCATION, ENCODING_OBFUSCATION, normalized);
    return categories.isEmpty()
        ? new PromptInjectionResult(false, Set.of())
        : new PromptInjectionResult(true, categories);
  }

  public PromptInjectionResult detectRetrievedChunk(final String chunk) {
    return detect(chunk);
  }

  private static void addIfMatched(
      final Set<PromptInjectionCategory> categories,
      final PromptInjectionCategory category,
      final Pattern pattern,
      final String text) {
    if (pattern.matcher(text).find()) {
      categories.add(category);
    }
  }

  private static String normalize(final String text) {
    final String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    return normalized.replaceAll("[\\p{Cf}]", "").replaceAll("\\s+", " ").trim();
  }
}
