package com.medassist.retrieval.application.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.retrieval.api.dto.SearchResponse;
import com.medassist.retrieval.application.CitationValidator;
import com.medassist.retrieval.application.RetrievalResponseMapper;
import com.medassist.retrieval.application.model.CitationCandidate;
import com.medassist.retrieval.application.model.CitationValidationResult;
import com.medassist.retrieval.application.model.ContextualRetrievalMode;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievalMode;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.application.model.SearchQuery;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

class SpringAiAnswerGeneratorTest {
  private static final UUID CHUNK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String CONTEXT_ONLY_MARKER = "CTX_ONLY_M2_3_BOUNDARY_MARKER";

  @Test
  void promptUsesTextAndSourceFieldsButNeverArbitraryChunkMetadata() {
    final SpringAiAnswerGenerator generator = generator();
    final RetrievedChunk chunk = chunk();

    final String prompt = generator.buildUserPrompt("What is the recommendation?", List.of(chunk));

    assertThat(prompt)
        .contains("What is the recommendation?")
        .contains("The source text")
        .contains("Example Publisher")
        .contains("Example title")
        .contains("2024-01-02")
        .doesNotContain(CONTEXT_ONLY_MARKER)
        .doesNotContain("arbitrary-secret-metadata");
  }

  @Test
  void citationValidationAndDisplayUseOriginalTextWithoutContextPrefixMarker() {
    final RetrievedChunk chunk = chunk();
    final CitationValidator validator = new CitationValidator();

    final CitationValidationResult originalCitation =
        validator
            .validate(
                List.of(new CitationCandidate(CHUNK_ID, VERSION_ID, "The source text", "direct")),
                List.of(chunk))
            .get(0);
    final CitationValidationResult contextCitation =
        validator
            .validate(
                List.of(new CitationCandidate(CHUNK_ID, VERSION_ID, CONTEXT_ONLY_MARKER, "direct")),
                List.of(chunk))
            .get(0);

    assertThat(originalCitation.valid()).isTrue();
    assertThat(contextCitation.valid()).isFalse();
    assertThat(chunk.text()).isEqualTo("The source text").doesNotContain(CONTEXT_ONLY_MARKER);

    final SearchResponse display =
        new RetrievalResponseMapper()
            .toResponse(new SearchOutcome(searchQuery(), List.of(chunk), 1L, 2L));
    assertThat(display.results()).hasSize(1);
    assertThat(display.results().get(0).text())
        .isEqualTo("The source text")
        .doesNotContain(CONTEXT_ONLY_MARKER);
  }

  @Test
  void parsesStructuredAnswerAndTokenUsage() {
    final SpringAiAnswerGenerator generator = generator();
    final String json =
        """
        {
          "answer": "Use the cited recommendation.",
          "citations": [{
            "chunkId": "00000000-0000-0000-0000-000000000001",
            "documentVersionId": "00000000-0000-0000-0000-000000000002",
            "quotedSpan": "The source text",
            "relevance": "direct"
          }],
          "sufficientEvidence": true,
          "tokenUsage": {"promptTokens": 12, "completionTokens": 8, "totalTokens": 20}
        }
        """;

    final GeneratedAnswer answer = generator.parseStructuredAnswer(json);

    assertThat(answer.answer()).isEqualTo("Use the cited recommendation.");
    assertThat(answer.sufficientEvidence()).isTrue();
    assertThat(answer.citations())
        .containsExactly(new GeneratedCitation(CHUNK_ID, VERSION_ID, "The source text", "direct"));
    assertThat(answer.tokenUsage()).isEqualTo(new TokenUsage(12, 8, 20));
  }

  @Test
  void invalidJsonFailsWithSafeException() {
    final SpringAiAnswerGenerator generator = generator();

    assertThatThrownBy(() -> generator.parseStructuredAnswer("not-json with sensitive prompt text"))
        .isInstanceOf(AnswerGenerationException.class)
        .hasMessage("Answer generation failed")
        .hasNoCause()
        .doesNotHaveToString("not-json with sensitive prompt text");
  }

  @Test
  void providerFailureDoesNotExposeItsMessageOrCause() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenThrow(new IllegalStateException("prompt-secret-and-content-secret"));
    final SpringAiAnswerGenerator generator =
        new SpringAiAnswerGenerator(
            chatClient, new ObjectMapper(), "system", Duration.ofSeconds(2));

    assertThatThrownBy(() -> generator.generate("question", List.of(chunk())))
        .isInstanceOf(AnswerGenerationException.class)
        .hasMessage("Answer generation failed")
        .hasNoCause()
        .doesNotHaveToString("prompt-secret-and-content-secret");
  }

  @Test
  void synchronousGenerationUsesChatClientOnce() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final String json = "{\"answer\":\"safe\",\"citations\":[],\"sufficientEvidence\":false}";
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenReturn(json);
    final SpringAiAnswerGenerator generator =
        new SpringAiAnswerGenerator(
            chatClient, new ObjectMapper(), "system", Duration.ofSeconds(2));

    final GeneratedAnswer answer = generator.generate("question", List.of(chunk()));

    assertThat(answer.answer()).isEqualTo("safe");
  }

  @Test
  void streamingParsesCollectedDeltasWithoutASecondCall() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final String first = "{\"answer\":\"safe\",\"citations\":[]";
    final String second = ",\"sufficientEvidence\":false}";
    when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just(first, second));
    final SpringAiAnswerGenerator generator =
        new SpringAiAnswerGenerator(
            chatClient, new ObjectMapper(), "system", Duration.ofSeconds(2));

    final List<GenerationEvent> events =
        generator.stream("question", List.of(chunk())).collectList().block();

    assertThat(events).hasSize(2);
    assertThat(events.get(0).delta()).isEqualTo("safe");
    assertThat(events.get(1).finalAnswer().answer()).isEqualTo("safe");
  }

  @Test
  void streamingEmitsOnlyDecodedAnswerTextAcrossFragmentedJsonEscapes() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(
            Flux.just(
                "{\"answer\":\"first\\nsec",
                "ond " + "\\" + "u00",
                "41\",\"citations\":[],\"sufficientEvidence\":false}"));
    final SpringAiAnswerGenerator generator =
        new SpringAiAnswerGenerator(
            chatClient, new ObjectMapper(), "system", Duration.ofSeconds(2));

    final List<GenerationEvent> events =
        generator.stream("question", List.of(chunk())).collectList().block();

    assertThat(events.stream().filter(GenerationEvent::isDelta).map(GenerationEvent::delta))
        .containsExactly("first\nsec", "ond ", "A");
    assertThat(events.get(events.size() - 1).finalAnswer().answer()).isEqualTo("first\nsecond A");
  }

  @Test
  void fakeAnswerGeneratorCanBeUsedBehindProviderNeutralBoundary() {
    final AnswerGenerator fake =
        new AnswerGenerator() {
          @Override
          public GeneratedAnswer generate(final String query, final List<RetrievedChunk> evidence) {
            return new GeneratedAnswer("fake", List.of(), false, TokenUsage.unknown());
          }

          @Override
          public Flux<GenerationEvent> stream(
              final String query, final List<RetrievedChunk> evidence) {
            return Flux.just(
                GenerationEvent.delta("raw"), GenerationEvent.complete(generate(query, evidence)));
          }
        };

    assertThat(fake.generate("question", List.of()).answer()).isEqualTo("fake");
    assertThat(
            fake.stream("question", List.of()).map(GenerationEvent::isFinal).collectList().block())
        .containsExactly(false, true);
  }

  private SpringAiAnswerGenerator generator() {
    return new SpringAiAnswerGenerator(
        mock(ChatClient.class), new ObjectMapper(), "system prompt", Duration.ofSeconds(2));
  }

  private RetrievedChunk chunk() {
    return new RetrievedChunk(
        CHUNK_ID,
        VERSION_ID,
        3,
        "Recommendations",
        "The source text",
        3,
        10,
        25,
        0.9,
        "HYBRID",
        "cosine",
        "guideline",
        "Example Publisher",
        "Example title",
        "v2",
        LocalDate.of(2024, 1, 2),
        "ACTIVE",
        false,
        1,
        1,
        0.9,
        0.8,
        0.85,
        Map.of("context_prefix", CONTEXT_ONLY_MARKER, "other", "arbitrary-secret-metadata"));
  }

  private SearchQuery searchQuery() {
    return new SearchQuery(
        "question",
        5,
        10,
        new RetrievalFilters(Set.of(), Set.of(), null, null, Set.of()),
        "anonymous",
        "model",
        "v1",
        "COSINE",
        RetrievalMode.VECTOR_ONLY,
        false,
        false,
        ContextualRetrievalMode.OFF,
        "structure-v1",
        3);
  }
}
