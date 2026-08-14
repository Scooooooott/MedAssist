package com.medassist.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.agent.config.DeidProperties;
import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import com.medassist.common.context.ExecutorFactory;
import com.medassist.common.resilience.ComponentPolicyTable;
import com.medassist.common.resilience.ResilienceExecutor;
import com.medassist.contracts.v1.AnonymizeResponse;
import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.domain.Role;
import io.grpc.Status;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcQueryDeidentifierTest {
  private static final DeidProperties PROPERTIES =
      new DeidProperties(true, "localhost:9002", Duration.ofSeconds(1), "SAFE_HARBOR_SURROGATE");
  private ResilienceExecutor resilienceExecutor;

  @BeforeEach
  void bindExecutionContext() {
    resilienceExecutor =
        new ResilienceExecutor(
            ComponentPolicyTable.conservativeDefaults(),
            ExecutorFactory.newVirtualThreadPerTaskExecutor());
    ContextCarrier.restore(
        new ExecutionContext("test-user", Set.of("CLINICIAN"), "request-id", "trace-id", Map.of()));
  }

  @AfterEach
  void clearExecutionContext() {
    ContextCarrier.clear();
    resilienceExecutor.close();
  }

  @Test
  void acceptsOnlyValidDeidentificationResponse() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), any())).thenReturn(stub);
    when(stub.anonymize(any()))
        .thenReturn(
            AnonymizeResponse.newBuilder().setText("safe query").setPolicyVersion("v1").build());

    final DeidentifiedQuery result =
        new GrpcQueryDeidentifier(stub, PROPERTIES, resilienceExecutor)
            .deidentify(
                "raw query", new DeidentificationMetadata("trace", "request", Role.CLINICIAN));

    assertThat(result.value()).isEqualTo("safe query");
    assertThat(result.originalQueryHash())
        .isEqualTo("sha256:d60b96b613cbbdbaca63bed8b87b762fba8e54b89cb7b1af2ed492a5691ad5d6");
  }

  @Test
  void rejectsErrorOrMissingPolicyVersion() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), any())).thenReturn(stub);
    when(stub.anonymize(any())).thenReturn(AnonymizeResponse.newBuilder().setText("safe").build());

    assertThatThrownBy(
            () ->
                new GrpcQueryDeidentifier(stub, PROPERTIES, resilienceExecutor)
                    .deidentify(
                        "raw", new DeidentificationMetadata("trace", "request", Role.RESEARCHER)))
        .isInstanceOf(DeidentificationException.class);
  }

  @Test
  void rejectsDirectCallsWithoutRequestMetadata() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    assertThatThrownBy(
            () -> new GrpcQueryDeidentifier(stub, PROPERTIES, resilienceExecutor).deidentify("raw"))
        .isInstanceOf(DeidentificationException.class);
  }

  @Test
  void failsClosedWhenDeidentificationServiceIsUnavailable() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), any())).thenReturn(stub);
    when(stub.anonymize(any()))
        .thenThrow(Status.UNAVAILABLE.withDescription("raw patient query").asRuntimeException());

    assertThatThrownBy(
            () ->
                new GrpcQueryDeidentifier(stub, PROPERTIES, resilienceExecutor)
                    .deidentify(
                        "raw patient query",
                        new DeidentificationMetadata("trace", "request", Role.CLINICIAN)))
        .isInstanceOf(DeidentificationException.class)
        .hasMessage("de-identification service unavailable")
        .hasMessageNotContaining("raw patient query");
    verify(stub, times(2)).anonymize(any());
  }
}
