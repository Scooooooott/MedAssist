package com.medassist.retrieval.application.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.retrieval.application.model.RetrievedChunk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Spring AI adapter that keeps provider details behind the AnswerGenerator boundary. */
public final class SpringAiAnswerGenerator implements AnswerGenerator {
  private final ChatClient chatClient;
  private final ObjectMapper objectMapper;
  private final String systemPrompt;
  private final Duration timeout;

  public SpringAiAnswerGenerator(
      final ChatClient chatClient,
      final ObjectMapper objectMapper,
      final String systemPrompt,
      final Duration timeout) {
    this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("system prompt is required");
    }
    if (timeout == null || timeout.isNegative() || timeout.toMillis() <= 0) {
      throw new IllegalArgumentException("positive timeout is required");
    }
    this.systemPrompt = systemPrompt;
    this.timeout = timeout;
  }

  @Override
  public GeneratedAnswer generate(final String query, final List<RetrievedChunk> evidence) {
    final String userPrompt = buildUserPrompt(query, evidence);
    final CompletableFuture<String> request =
        CompletableFuture.supplyAsync(() -> callModel(userPrompt));
    try {
      return parseStructuredAnswer(request.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AnswerGenerationException();
    } catch (TimeoutException | ExecutionException exception) {
      throw new AnswerGenerationException();
    }
  }

  @Override
  public Flux<GenerationEvent> stream(final String query, final List<RetrievedChunk> evidence) {
    final String userPrompt = buildUserPrompt(query, evidence);
    return Flux.defer(
            () -> {
              final StringBuilder responseBuffer = new StringBuilder();
              final JsonAnswerDeltaExtractor answerExtractor = new JsonAnswerDeltaExtractor();
              return chatClient.prompt().system(systemPrompt).user(userPrompt).stream()
                  .content()
                  .doOnNext(responseBuffer::append)
                  .map(answerExtractor::feed)
                  .filter(delta -> !delta.isEmpty())
                  .map(GenerationEvent::delta)
                  .timeout(timeout)
                  .concatWith(
                      Mono.fromSupplier(
                          () ->
                              GenerationEvent.complete(
                                  parseStructuredAnswer(responseBuffer.toString()))));
            })
        .onErrorMap(
            exception ->
                exception instanceof AnswerGenerationException
                    ? exception
                    : new AnswerGenerationException());
  }

  String buildUserPrompt(final String query, final List<RetrievedChunk> evidence) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query is required");
    }
    if (evidence == null) {
      throw new IllegalArgumentException("evidence is required");
    }

    final StringBuilder prompt = new StringBuilder();
    prompt.append("Question:\n").append(query).append("\n\nEvidence:\n");
    for (int index = 0; index < evidence.size(); index++) {
      final RetrievedChunk chunk =
          Objects.requireNonNull(evidence.get(index), "evidence item is required");
      prompt
          .append("[Evidence ")
          .append(index + 1)
          .append("]\nchunk_id: ")
          .append(chunk.chunkId())
          .append("\ndocument_version_id: ")
          .append(chunk.documentVersionId())
          .append("\nordinal: ")
          .append(chunk.ordinal())
          .append("\nsection_path: ")
          .append(valueOrUnknown(chunk.sectionPath()))
          .append("\nsource_title: ")
          .append(valueOrUnknown(chunk.sourceTitle()))
          .append("\npublisher: ")
          .append(valueOrUnknown(chunk.publisher()))
          .append("\nversion: ")
          .append(valueOrUnknown(chunk.version()))
          .append("\neffective_date: ")
          .append(chunk.effectiveDate() == null ? "Unknown" : chunk.effectiveDate())
          .append("\ndocument_status: ")
          .append(valueOrUnknown(chunk.documentStatus()))
          .append("\nstale: ")
          .append(chunk.stale())
          .append("\ntext:\n")
          .append(Objects.requireNonNull(chunk.text(), "evidence text is required"))
          .append("\n\n");
    }
    prompt.append(
        "Return only JSON with fields answer, citations, and sufficientEvidence. "
            + "Each citation must identify an evidence chunk and quote text from that chunk.");
    return prompt.toString();
  }

  GeneratedAnswer parseStructuredAnswer(final String rawJson) {
    try {
      final JsonNode root = objectMapper.readTree(rawJson);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("structured answer is not an object");
      }
      final JsonNode answerNode = required(root, "answer");
      final JsonNode citationsNode = required(root, "citations");
      final JsonNode sufficientEvidenceNode = required(root, "sufficientEvidence");
      if (!answerNode.isTextual()
          || answerNode.textValue().isBlank()
          || !citationsNode.isArray()
          || !sufficientEvidenceNode.isBoolean()) {
        throw new IllegalArgumentException("structured answer has invalid fields");
      }

      final List<GeneratedCitation> citations = new ArrayList<>();
      for (final JsonNode citationNode : citationsNode) {
        if (!citationNode.isObject()) {
          throw new IllegalArgumentException("citation is not an object");
        }
        citations.add(
            new GeneratedCitation(
                UUID.fromString(requiredText(citationNode, "chunkId")),
                UUID.fromString(requiredText(citationNode, "documentVersionId")),
                requiredText(citationNode, "quotedSpan"),
                requiredText(citationNode, "relevance")));
      }
      return new GeneratedAnswer(
          answerNode.textValue(),
          citations,
          sufficientEvidenceNode.booleanValue(),
          parseTokenUsage(root.get("tokenUsage")));
    } catch (AnswerGenerationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new AnswerGenerationException();
    }
  }

  private String callModel(final String userPrompt) {
    try {
      final String content =
          chatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
      if (content == null || content.isBlank()) {
        throw new IllegalArgumentException("empty model response");
      }
      return content;
    } catch (AnswerGenerationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new AnswerGenerationException();
    }
  }

  private TokenUsage parseTokenUsage(final JsonNode node) {
    if (node == null || node.isNull()) {
      return TokenUsage.unknown();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("token usage is not an object");
    }
    return new TokenUsage(
        integerOrUnknown(node.get("promptTokens")),
        integerOrUnknown(node.get("completionTokens")),
        integerOrUnknown(node.get("totalTokens")));
  }

  private int integerOrUnknown(final JsonNode node) {
    if (node == null || node.isNull()) {
      return -1;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
      throw new IllegalArgumentException("token usage field is invalid");
    }
    return node.intValue();
  }

  private JsonNode required(final JsonNode object, final String field) {
    final JsonNode value = object.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("required field is missing");
    }
    return value;
  }

  private String requiredText(final JsonNode object, final String field) {
    final JsonNode value = required(object, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("required text field is invalid");
    }
    return value.textValue();
  }

  private String valueOrUnknown(final String value) {
    return value == null || value.isBlank() ? "Unknown" : value;
  }
}
