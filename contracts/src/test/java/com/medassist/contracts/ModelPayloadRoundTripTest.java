package com.medassist.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.medassist.contracts.v1.EmbedResponse;
import com.medassist.contracts.v1.FloatVector;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ModelPayloadRoundTripTest {
  @Test
  void embedResponseRoundTripsOneHundredVectorsWithExpectedDimension() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          final EmbedResponse.Builder response =
              EmbedResponse.newBuilder()
                  .setModelName("BAAI/bge-m3")
                  .setModelVersion("m0-contract-test")
                  .setDimension(1024);
          for (int vectorIndex = 0; vectorIndex < 100; vectorIndex++) {
            final FloatVector.Builder vector = FloatVector.newBuilder();
            for (int dimensionIndex = 0; dimensionIndex < 1024; dimensionIndex++) {
              vector.addValues(vectorIndex + (dimensionIndex / 1024.0f));
            }
            response.addVectors(vector);
          }

          final EmbedResponse parsed = EmbedResponse.parseFrom(response.build().toByteArray());

          assertEquals(100, parsed.getVectorsCount());
          assertEquals(1024, parsed.getDimension());
          assertEquals(1024, parsed.getVectors(99).getValuesCount());
          assertEquals(99.999f, parsed.getVectors(99).getValues(1023), 0.0001f);
        });
  }
}
