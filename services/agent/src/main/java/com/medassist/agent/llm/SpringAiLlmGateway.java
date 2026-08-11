package com.medassist.agent.llm;

import com.medassist.agent.config.LlmProperties;
import com.medassist.agent.security.ContentClass;
import com.medassist.agent.security.DefaultEgressGuard;
import com.medassist.agent.security.EgressDecision;
import com.medassist.agent.security.EgressGuard;
import com.medassist.agent.security.EgressRequest;
import com.medassist.agent.security.EgressSource;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Spring AI adapter; callers depend only on {@link LlmGateway}. */
public final class SpringAiLlmGateway implements LlmGateway {
  private static final String LOCAL_MODEL = "LOCAL_MODEL";
  private static final String EXTERNAL_LLM = "EXTERNAL_LLM";

  private final ChatClient chatClient;
  private final LlmProperties properties;
  private final EgressGuard egressGuard;

  public SpringAiLlmGateway(final ChatClient chatClient, final LlmProperties properties) {
    this(chatClient, properties, new DefaultEgressGuard());
  }

  public SpringAiLlmGateway(
      final ChatClient chatClient, final LlmProperties properties, final EgressGuard egressGuard) {
    this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.egressGuard = Objects.requireNonNull(egressGuard, "egressGuard");
  }

  @Override
  public LlmResponse complete(final LlmRequest request) {
    Objects.requireNonNull(request, "request");
    final LlmCallMetadata metadata = resolveMetadata(request.metadata());
    enforceEgress(request);
    final CompletableFuture<SpringAiCallResult> call =
        CompletableFuture.supplyAsync(
            () -> {
              final ChatClient.CallResponseSpec response =
                  chatClient
                      .prompt()
                      .system(request.systemPrompt())
                      .user(request.userPrompt())
                      .call();
              return new SpringAiCallResult(response.content(), response.chatResponse());
            });
    try {
      final SpringAiCallResult result =
          call.get(metadata.timeout().toMillis(), TimeUnit.MILLISECONDS);
      final String content = result.content();
      if (content == null || content.isBlank()) {
        throw LlmGatewayException.invalidResponse();
      }
      final LlmUsage usage = extractUsage(result.chatResponse(), metadata);
      return new LlmResponse(content, metadata, usage, calculateCost(usage));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw LlmGatewayException.providerError(exception);
    } catch (TimeoutException exception) {
      call.cancel(true);
      throw LlmGatewayException.timeout(exception);
    } catch (ExecutionException exception) {
      throw LlmGatewayException.providerError(exception.getCause());
    }
  }

  private LlmCallMetadata resolveMetadata(final LlmCallMetadata requested) {
    if (requested == null || "unconfigured".equals(requested.provider())) {
      return properties.metadata();
    }
    return requested;
  }

  private void enforceEgress(final LlmRequest request) {
    final String destination = resolveDestination(properties.provider());
    try {
      requireAllowed(
          new EgressRequest(
              destination,
              ContentClass.DEIDENTIFIED_QUERY,
              EgressSource.SYSTEM_PROMPT,
              request.systemPrompt(),
              false));
      requireAllowed(
          new EgressRequest(
              destination,
              ContentClass.DEIDENTIFIED_QUERY,
              EgressSource.USER_QUERY,
              request.userPrompt(),
              false));
    } catch (LlmGatewayException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw LlmGatewayException.egressBlocked();
    }
  }

  private void requireAllowed(final EgressRequest request) {
    final EgressDecision decision = egressGuard.inspect(request);
    if (decision == null || !decision.allowed()) {
      throw LlmGatewayException.egressBlocked();
    }
  }

  private static String resolveDestination(final String provider) {
    if (provider == null || provider.isBlank()) {
      return EXTERNAL_LLM;
    }
    final String normalized = provider.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "local", "local_model", "local_llm", "ollama", "llama_cpp", "llamacpp" -> LOCAL_MODEL;
      default -> EXTERNAL_LLM;
    };
  }

  private LlmUsage extractUsage(final ChatResponse chatResponse, final LlmCallMetadata metadata) {
    if (chatResponse == null
        || chatResponse.getMetadata() == null
        || chatResponse.getMetadata().getUsage() == null) {
      return LlmUsage.unknown(metadata);
    }
    final Usage usage = chatResponse.getMetadata().getUsage();
    return new LlmUsage(
        valueOrUnknown(usage.getPromptTokens()),
        valueOrUnknown(usage.getCompletionTokens()),
        valueOrUnknown(usage.getTotalTokens()),
        metadata);
  }

  private LlmCost calculateCost(final LlmUsage usage) {
    if (usage.inputTokens() < 0
        || usage.outputTokens() < 0
        || (properties.inputCostPer1kTokens() == 0 && properties.outputCostPer1kTokens() == 0)) {
      return LlmCost.unknown(usage.metadata());
    }
    final BigDecimal inputCost =
        BigDecimal.valueOf(usage.inputTokens())
            .divide(BigDecimal.valueOf(1000))
            .multiply(BigDecimal.valueOf(properties.inputCostPer1kTokens()));
    final BigDecimal outputCost =
        BigDecimal.valueOf(usage.outputTokens())
            .divide(BigDecimal.valueOf(1000))
            .multiply(BigDecimal.valueOf(properties.outputCostPer1kTokens()));
    return new LlmCost(
        true, inputCost, outputCost, inputCost.add(outputCost), "USD", usage.metadata());
  }

  private long valueOrUnknown(final Integer value) {
    return value == null || value < 0 ? -1 : value;
  }

  private record SpringAiCallResult(String content, ChatResponse chatResponse) {}
}
