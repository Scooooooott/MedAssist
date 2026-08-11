package com.medassist.agent.application;

import com.medassist.agent.config.DeidProperties;
import com.medassist.contracts.v1.AnonymizeRequest;
import com.medassist.contracts.v1.AnonymizeResponse;
import com.medassist.contracts.v1.DeidPolicy;
import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.contracts.v1.RequestMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Blocking adapter for the mandatory ingress de-identification boundary. */
public final class GrpcQueryDeidentifier implements QueryDeidentifier {
  private final DeidServiceGrpc.DeidServiceBlockingStub stub;
  private final DeidProperties properties;

  public GrpcQueryDeidentifier(
      final DeidServiceGrpc.DeidServiceBlockingStub stub, final DeidProperties properties) {
    this.stub = Objects.requireNonNull(stub, "stub");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public DeidentifiedQuery deidentify(final String rawQuery) {
    throw new DeidentificationException("request metadata is required for de-identification");
  }

  @Override
  public DeidentifiedQuery deidentify(
      final String rawQuery, final DeidentificationMetadata metadata) {
    if (rawQuery == null || rawQuery.isBlank()) {
      throw new DeidentificationException("raw query must not be blank");
    }
    Objects.requireNonNull(metadata, "metadata");
    final AnonymizeRequest request =
        AnonymizeRequest.newBuilder()
            .setMetadata(
                RequestMetadata.newBuilder()
                    .setTraceId(metadata.traceId())
                    .setRequestId(metadata.requestId())
                    .setRole(metadata.role().name())
                    .build())
            .setText(rawQuery)
            .setPolicy(toPolicy(properties.policy()))
            .build();
    final AnonymizeResponse response;
    try {
      response =
          stub.withDeadlineAfter(properties.timeout().toNanos(), TimeUnit.NANOSECONDS)
              .anonymize(request);
    } catch (RuntimeException exception) {
      throw new DeidentificationException("de-identification service unavailable", exception);
    }
    if (response == null || response.hasError()) {
      throw new DeidentificationException("de-identification returned an error");
    }
    if (response.getPolicyVersion().isBlank() || response.getText().isBlank()) {
      throw new DeidentificationException("de-identification returned an invalid response");
    }
    return new DeidentifiedQuery(response.getText(), sha256(rawQuery));
  }

  private static DeidPolicy toPolicy(final String policy) {
    return switch (policy) {
      case "SAFE_HARBOR_SURROGATE", "DEID_POLICY_SAFE_HARBOR_SURROGATE" ->
          DeidPolicy.DEID_POLICY_SAFE_HARBOR_SURROGATE;
      case "SAFE_HARBOR_REDACT", "DEID_POLICY_SAFE_HARBOR_REDACT" ->
          DeidPolicy.DEID_POLICY_SAFE_HARBOR_REDACT;
      default -> throw new DeidentificationException("unsupported de-identification policy");
    };
  }

  private static String sha256(final String value) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(digest.length * 2);
      for (final byte valueByte : digest) {
        hex.append(String.format("%02x", valueByte));
      }
      return "sha256:" + hex;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
