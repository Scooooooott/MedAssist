package com.medassist.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.agent.config.LlmProperties;
import com.medassist.agent.security.DefaultEgressGuard;
import com.medassist.agent.security.EgressRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

class LlmGatewayTest {
  private static final LlmProperties PROPERTIES =
      new LlmProperties(true, "test-provider", "test-model", Duration.ofSeconds(1), 0, 0);

  @Test
  void unavailableGatewayFailsClosed() {
    final LlmGateway gateway = new UnavailableLlmGateway();

    assertThatThrownBy(() -> gateway.complete(new LlmRequest("system", "question")))
        .isInstanceOf(LlmGatewayException.class)
        .extracting(exception -> ((LlmGatewayException) exception).reason())
        .isEqualTo(LlmFailureReason.UNAVAILABLE);
  }

  @Test
  void successfulCallReturnsConfiguredMetadataWithoutSensitiveToStringContent() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt().system(anyString()).user(anyString()).call()).thenReturn(callResponse);
    when(callResponse.content()).thenReturn("safe answer");
    when(callResponse.chatResponse()).thenReturn(null);
    final SpringAiLlmGateway gateway = new SpringAiLlmGateway(chatClient, PROPERTIES);
    final LlmRequest request =
        new LlmRequest("system", "secret clinical question", LlmCallMetadata.unconfigured());

    final LlmResponse response = gateway.complete(request);

    assertThat(response.content()).isEqualTo("safe answer");
    assertThat(response.metadata())
        .isEqualTo(new LlmCallMetadata("test-provider", "test-model", Duration.ofSeconds(1)));
    assertThat(response.usage()).isEqualTo(LlmUsage.unknown(response.metadata()));
    assertThat(response.cost()).isEqualTo(LlmCost.unknown(response.metadata()));
    assertThat(request.toString()).doesNotContain("secret clinical question");
    assertThat(response.toString()).doesNotContain("safe answer");
  }

  @Test
  void injectedExecutorRunsProviderCall() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt().system(anyString()).user(anyString()).call()).thenReturn(callResponse);
    when(callResponse.content()).thenReturn("safe answer");
    when(callResponse.chatResponse()).thenReturn(null);
    final AtomicBoolean executorUsed = new AtomicBoolean();
    final Executor executor =
        command -> {
          executorUsed.set(true);
          command.run();
        };
    final SpringAiLlmGateway gateway =
        new SpringAiLlmGateway(chatClient, PROPERTIES, new DefaultEgressGuard(), executor);

    gateway.complete(new LlmRequest("system", "question"));

    assertThat(executorUsed).isTrue();
  }

  @Test
  void successfulUsageIsMappedAndCostIsCalculatedWhenRatesAreConfigured() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    final ChatResponse chatResponse = mock(ChatResponse.class);
    final ChatResponseMetadata responseMetadata = mock(ChatResponseMetadata.class);
    when(chatClient.prompt().system(anyString()).user(anyString()).call()).thenReturn(callResponse);
    when(callResponse.content()).thenReturn("safe answer");
    when(callResponse.chatResponse()).thenReturn(chatResponse);
    when(chatResponse.getMetadata()).thenReturn(responseMetadata);
    when(responseMetadata.getUsage()).thenReturn(new DefaultUsage(12, 8, 20));
    final LlmProperties properties =
        new LlmProperties(true, "test-provider", "test-model", Duration.ofSeconds(1), 2, 4);

    final LlmResponse response =
        new SpringAiLlmGateway(chatClient, properties)
            .complete(new LlmRequest("system", "question"));

    assertThat(response.usage().inputTokens()).isEqualTo(12);
    assertThat(response.usage().outputTokens()).isEqualTo(8);
    assertThat(response.usage().totalTokens()).isEqualTo(20);
    assertThat(response.usage().metadata()).isEqualTo(response.metadata());
    assertThat(response.cost().known()).isTrue();
    assertThat(response.cost().totalCost()).isEqualByComparingTo("0.056");
    assertThat(response.cost().metadata()).isEqualTo(response.metadata());
  }

  @Test
  void providerFailureIsMappedWithoutExposingProviderMessage() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenThrow(
            new IllegalStateException("provider failure containing secret clinical question"));
    final SpringAiLlmGateway gateway = new SpringAiLlmGateway(chatClient, PROPERTIES);

    assertThatThrownBy(() -> gateway.complete(new LlmRequest("system", "secret clinical question")))
        .isInstanceOf(LlmGatewayException.class)
        .satisfies(
            exception -> {
              final LlmGatewayException gatewayException = (LlmGatewayException) exception;
              assertThat(gatewayException.reason()).isEqualTo(LlmFailureReason.PROVIDER_ERROR);
              assertThat(gatewayException).hasMessage("LLM provider call failed");
              assertThat(gatewayException).hasMessageNotContaining("secret clinical question");
              assertThat(gatewayException.getCause()).isNull();
            });
  }

  @Test
  void egressDenialBlocksProviderCallWithGenericGatewayReason() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final SpringAiLlmGateway gateway = new SpringAiLlmGateway(chatClient, PROPERTIES);
    final String sensitivePrompt = "Patient John Smith, MRN-12345";

    assertThatThrownBy(() -> gateway.complete(new LlmRequest("system", sensitivePrompt)))
        .isInstanceOf(LlmGatewayException.class)
        .satisfies(
            exception -> {
              final LlmGatewayException gatewayException = (LlmGatewayException) exception;
              assertThat(gatewayException.reason()).isEqualTo(LlmFailureReason.EGRESS_BLOCKED);
              assertThat(gatewayException).hasMessage("LLM gateway request blocked");
              assertThat(gatewayException).hasMessageNotContaining(sensitivePrompt);
              assertThat(gatewayException.getCause()).isNull();
            });

    verify(chatClient, never()).prompt();
  }

  @Test
  void configuredExternalProviderIsPresentedToTheEgressGuard() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt().system(anyString()).user(anyString()).call()).thenReturn(callResponse);
    when(callResponse.content()).thenReturn("safe answer");
    when(callResponse.chatResponse()).thenReturn(null);
    final List<EgressRequest> inspected = new ArrayList<>();
    final SpringAiLlmGateway gateway =
        new SpringAiLlmGateway(
            chatClient,
            PROPERTIES,
            request -> {
              inspected.add(request);
              return new DefaultEgressGuard().inspect(request);
            });

    gateway.complete(new LlmRequest("system", "question"));

    assertThat(inspected)
        .hasSize(2)
        .allSatisfy(request -> assertThat(request.destination()).isEqualTo("EXTERNAL_LLM"));
  }

  @Test
  void configuredLocalProviderIsPresentedToTheEgressGuard() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt().system(anyString()).user(anyString()).call()).thenReturn(callResponse);
    when(callResponse.content()).thenReturn("safe answer");
    when(callResponse.chatResponse()).thenReturn(null);
    final LlmProperties localProperties =
        new LlmProperties(true, "ollama", "local-model", Duration.ofSeconds(1), 0, 0);
    final List<EgressRequest> inspected = new ArrayList<>();
    final SpringAiLlmGateway gateway =
        new SpringAiLlmGateway(
            chatClient,
            localProperties,
            request -> {
              inspected.add(request);
              return new DefaultEgressGuard().inspect(request);
            });

    gateway.complete(new LlmRequest("system", "question"));

    assertThat(inspected)
        .hasSize(2)
        .allSatisfy(request -> assertThat(request.destination()).isEqualTo("LOCAL_MODEL"));
  }

  @Test
  void egressGuardFailureFailsClosedWithoutExposingGuardText() {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final SpringAiLlmGateway gateway =
        new SpringAiLlmGateway(
            chatClient,
            PROPERTIES,
            request -> {
              throw new IllegalStateException("provider text containing sensitive data");
            });

    assertThatThrownBy(() -> gateway.complete(new LlmRequest("system", "question")))
        .isInstanceOf(LlmGatewayException.class)
        .satisfies(
            exception -> {
              final LlmGatewayException gatewayException = (LlmGatewayException) exception;
              assertThat(gatewayException.reason()).isEqualTo(LlmFailureReason.EGRESS_BLOCKED);
              assertThat(gatewayException).hasMessage("LLM gateway request blocked");
              assertThat(gatewayException).hasMessageNotContaining("sensitive data");
              assertThat(gatewayException.getCause()).isNull();
            });

    verify(chatClient, never()).prompt();
  }

  @Test
  void timeoutIsMappedAndInFlightCallCanBeReleased() throws Exception {
    final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenAnswer(
            invocation -> {
              started.countDown();
              release.await(2, TimeUnit.SECONDS);
              return "late answer";
            });
    final LlmProperties properties =
        new LlmProperties(true, "test-provider", "test-model", Duration.ofMillis(25), 0, 0);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final SpringAiLlmGateway gateway =
          new SpringAiLlmGateway(chatClient, properties, new DefaultEgressGuard(), executor);

      assertThatThrownBy(() -> gateway.complete(new LlmRequest("system", "question")))
          .isInstanceOf(LlmGatewayException.class)
          .extracting(exception -> ((LlmGatewayException) exception).reason())
          .isEqualTo(LlmFailureReason.TIMEOUT);
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
      release.countDown();
    }
  }
}
