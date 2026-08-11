package com.medassist.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class StructuredDraftParser {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> DRAFT_FIELDS = Set.of("answer", "citations");
  private static final Set<String> CITATION_FIELDS = Set.of("chunkId", "quotedSpan");

  private StructuredDraftParser() {}

  static ParsedDraft parse(final String response) {
    try {
      final JsonNode root = OBJECT_MAPPER.readTree(response);
      if (root == null || !root.isObject() || !hasExactly(root, DRAFT_FIELDS)) {
        throw new InvalidDraftFormatException();
      }
      final JsonNode answerNode = root.get("answer");
      final JsonNode citationsNode = root.get("citations");
      if (answerNode == null
          || !answerNode.isTextual()
          || answerNode.asText().isBlank()
          || citationsNode == null
          || !citationsNode.isArray()) {
        throw new InvalidDraftFormatException();
      }
      final List<StructuredCitation> citations =
          java.util.stream.StreamSupport.stream(citationsNode.spliterator(), false)
              .map(StructuredDraftParser::parseCitation)
              .toList();
      return new ParsedDraft(answerNode.asText(), citations);
    } catch (final JsonProcessingException | IllegalArgumentException exception) {
      throw new InvalidDraftFormatException();
    }
  }

  private static StructuredCitation parseCitation(final JsonNode citationNode) {
    if (citationNode == null
        || !citationNode.isObject()
        || !hasExactly(citationNode, CITATION_FIELDS)) {
      throw new InvalidDraftFormatException();
    }
    final JsonNode chunkIdNode = citationNode.get("chunkId");
    final JsonNode quotedSpanNode = citationNode.get("quotedSpan");
    if (chunkIdNode == null
        || !chunkIdNode.isTextual()
        || quotedSpanNode == null
        || !quotedSpanNode.isTextual()
        || quotedSpanNode.asText().isBlank()) {
      throw new InvalidDraftFormatException();
    }
    return new StructuredCitation(UUID.fromString(chunkIdNode.asText()), quotedSpanNode.asText());
  }

  private static boolean hasExactly(final JsonNode node, final Set<String> expectedFields) {
    final Set<String> actualFields = new HashSet<>();
    node.fieldNames().forEachRemaining(actualFields::add);
    return actualFields.equals(expectedFields);
  }

  record ParsedDraft(String answer, List<StructuredCitation> citations) {}

  record StructuredCitation(UUID chunkId, String quotedSpan) {}
}

final class InvalidDraftFormatException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
