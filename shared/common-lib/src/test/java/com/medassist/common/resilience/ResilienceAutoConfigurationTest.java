package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResilienceAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void bindsOverridesAndCreatesOneManagedRuntime() {
    final AtomicReference<ExecutorService> timeoutExecutor = new AtomicReference<>();

    contextRunner
        .withPropertyValues(
            "medassist.resilience.components.vector-retrieval.timeout=750ms",
            "medassist.resilience.components.vector-retrieval.bulkhead.max-concurrent-calls=9")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ResilienceExecutor.class);
              assertThat(context).hasSingleBean(ComponentPolicyTable.class);
              assertThat(
                      context
                          .getBean(ComponentPolicyTable.class)
                          .require(ResilienceComponent.VECTOR_RETRIEVAL)
                          .timeout()
                          .duration())
                  .isEqualTo(Duration.ofMillis(750));
              timeoutExecutor.set(
                  context.getBean("resilienceTimeoutExecutor", ExecutorService.class));
              assertThat(timeoutExecutor.get().isShutdown()).isFalse();
            });

    assertThat(timeoutExecutor.get().isShutdown()).isTrue();
  }
}
