package com.medassist.integration.faults;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Guards M5 production wiring so fixture-level evidence cannot hide an integration gap. */
@Tag("fault-nightly")
class M5RequirementGapGuardTest {

  @Test
  void degradationRecorderWiresAllSafeObservabilityProjections() throws IOException {
    final Path root = repositoryRoot();
    final String autoConfiguration =
        read(
            root.resolve(
                "shared/common-lib/src/main/java/com/medassist/common/resilience/"
                    + "DegradationObservabilityAutoConfiguration.java"));
    final String recorder =
        read(
            root.resolve(
                "shared/common-lib/src/main/java/com/medassist/common/resilience/"
                    + "ObservedDegradationRecorder.java"));
    final String imports =
        read(
            root.resolve(
                "shared/common-lib/src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"));

    assertTrue(autoConfiguration.contains("@Bean"));
    assertTrue(autoConfiguration.contains("new ObservedDegradationRecorder"));
    assertTrue(imports.contains("DegradationObservabilityAutoConfiguration"));
    assertTrue(recorder.contains("medassist.degradation.events"));
    assertTrue(recorder.contains("degradation.code"));
    assertTrue(recorder.contains("auditSink.publish(event)"));
    assertTrue(recorder.contains("trajectorySink.publish(event)"));
    assertTrue(
        read(root.resolve(
                "shared/common-lib/src/main/java/com/medassist/common/resilience/"
                    + "DegradationObservabilityAutoConfiguration.java"))
            .contains("new BoundedDegradationTrajectorySink"));
    assertTrue(
        read(root.resolve(
                "shared/audit-client/src/main/java/com/medassist/auditclient/resilience/"
                    + "AuditClientDegradationSink.java"))
            .contains("implements DegradationAuditSink"));
  }

  @Test
  void auditKafkaWireFormatUsesProtobufAndSchemaRegistryContract() throws IOException {
    final Path root = repositoryRoot();
    final String codec =
        read(
            root.resolve(
                "services/audit-governance/src/main/java/com/medassist/auditgovernance/transport/"
                    + "AuditEventCodec.java"));
    final String publisher =
        read(
            root.resolve(
                "services/audit-governance/src/main/java/com/medassist/auditgovernance/transport/"
                    + "KafkaAuditEventPublisher.java"));
    final String configuration =
        read(root.resolve("services/audit-governance/src/main/resources/application.yml"));

    final boolean protobufCodec =
        codec.contains("AuditEventEnvelope") && codec.contains("byte[] encode");
    final String compose = read(root.resolve("deploy/compose/compose.events.yml"));
    assertTrue(protobufCodec);
    assertTrue(publisher.contains("KafkaTemplate<String, byte[]>"));
    assertTrue(
        configuration.contains(
            "value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer"));
    assertTrue(
        compose.contains("rpk registry schema create audit-events-value")
            && compose.contains("--type protobuf")
            && compose.contains("BACKWARD"));
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("REQUIREMENTS-FULL.md"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("cannot locate repository root");
  }

  private static String read(final Path path) throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
