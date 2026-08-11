package com.medassist.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medassist.agent.config.DeidProperties;
import com.medassist.contracts.v1.AnonymizeResponse;
import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.domain.Role;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GrpcQueryDeidentifierTest {
  private static final DeidProperties PROPERTIES =
      new DeidProperties(true, "localhost:9002", Duration.ofSeconds(1), "SAFE_HARBOR_SURROGATE");

  @Test
  void acceptsOnlyValidDeidentificationResponse() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), any())).thenReturn(stub);
    when(stub.anonymize(any()))
        .thenReturn(
            AnonymizeResponse.newBuilder().setText("safe query").setPolicyVersion("v1").build());

    final DeidentifiedQuery result =
        new GrpcQueryDeidentifier(stub, PROPERTIES)
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
                new GrpcQueryDeidentifier(stub, PROPERTIES)
                    .deidentify(
                        "raw", new DeidentificationMetadata("trace", "request", Role.RESEARCHER)))
        .isInstanceOf(DeidentificationException.class);
  }

  @Test
  void rejectsDirectCallsWithoutRequestMetadata() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    assertThatThrownBy(() -> new GrpcQueryDeidentifier(stub, PROPERTIES).deidentify("raw"))
        .isInstanceOf(DeidentificationException.class);
  }
}
