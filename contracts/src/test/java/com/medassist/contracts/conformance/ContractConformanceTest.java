package com.medassist.contracts.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.medassist.contracts.v1.AnonymizeRequest;
import com.medassist.contracts.v1.AnonymizeResponse;
import com.medassist.contracts.v1.DeidPolicy;
import com.medassist.contracts.v1.DetectRequest;
import com.medassist.contracts.v1.DetectResponse;
import com.medassist.contracts.v1.EmbedRequest;
import com.medassist.contracts.v1.EmbedResponse;
import com.medassist.contracts.v1.EmbeddingInputType;
import com.medassist.contracts.v1.ParseDocumentRequest;
import com.medassist.contracts.v1.ParseDocumentResponse;
import com.medassist.contracts.v1.RerankRequest;
import com.medassist.contracts.v1.RerankResponse;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContractConformanceTest {
  private static final Path FIXTURES = Path.of(System.getProperty("basedir"), "conformance", "v1");

  @Test
  void sharedFixturesParseAndRoundTripInJava() throws Exception {
    for (FixtureCase fixture : loadCases()) {
      final Message request = parse(fixture.request(), prototype(fixture, true));
      assertEquals(request, roundTrip(request), fixture.identity() + " request round-trip");

      if (fixture.hasResponse()) {
        final Message response = parse(fixture.response(), prototype(fixture, false));
        assertEquals(response, roundTrip(response), fixture.identity() + " response round-trip");
        assertEquals("OK", fixture.grpcStatus(), fixture.identity() + " response status");
        if (!fixture.errorCode().isEmpty()) {
          assertEquals(
              fixture.errorCode(), responseErrorCode(response), fixture.identity() + " error code");
        }
      } else {
        assertNotEquals("OK", fixture.grpcStatus(), fixture.identity() + " missing response");
        assertEquals(
            fixture.grpcStatus(),
            fixture.errorCode(),
            fixture.identity() + " transport error code");
      }
      assertFalse(fixture.trigger().isBlank(), fixture.identity() + " trigger condition");
    }
  }

  @Test
  void everyContractCoversCategoriesBoundariesAndRegisteredErrorCodes() throws Exception {
    final Properties manifest = loadManifest();
    final List<FixtureCase> cases = loadCases();
    final Set<String> boundaryKinds = values(manifest, "boundary_kinds");

    for (String contract : values(manifest, "contracts")) {
      final List<FixtureCase> contractCases =
          cases.stream().filter(fixture -> contract.equals(fixture.contract())).toList();
      assertFalse(contractCases.isEmpty(), contract + " fixtures");
      assertEquals(
          Set.of("normal", "boundary", "error"),
          contractCases.stream().map(FixtureCase::category).collect(Collectors.toSet()),
          contract + " scenario categories");
      assertEquals(
          boundaryKinds,
          contractCases.stream()
              .flatMap(fixture -> fixture.boundaries().stream())
              .collect(Collectors.toSet()),
          contract + " boundary kinds");
      assertEquals(
          values(manifest, contract + ".error_codes"),
          contractCases.stream()
              .map(FixtureCase::errorCode)
              .filter(code -> !code.isEmpty())
              .collect(Collectors.toSet()),
          contract + " registered error codes");
    }
  }

  @Test
  void fixturesCoverEveryReachableSchemaField() throws Exception {
    final Set<String> expected = new LinkedHashSet<>();
    final Set<String> describedMessages = new HashSet<>();
    for (FixtureCase fixture : loadCases()) {
      collectDescriptorFields(
          prototype(fixture, true).getDescriptorForType(), describedMessages, expected);
      collectDescriptorFields(
          prototype(fixture, false).getDescriptorForType(), describedMessages, expected);
    }

    final Set<String> covered = new LinkedHashSet<>();
    for (FixtureCase fixture : loadCases()) {
      collectPopulatedFields(
          parse(fixture.request(), prototype(fixture, true)), new HashSet<>(), covered);
      if (fixture.hasResponse()) {
        collectPopulatedFields(
            parse(fixture.response(), prototype(fixture, false)), new HashSet<>(), covered);
      }
    }
    covered.addAll(values(loadManifest(), "default_fields"));

    assertEquals(expected, covered, "new or renamed protobuf fields require a shared fixture");
  }

  @Test
  void floatingPointValuesPreserveBitsAndDeclaredPrecision() throws Exception {
    final FixtureCase fixture = caseById("embedding", "normal-full");
    final EmbedResponse expected =
        (EmbedResponse) parse(fixture.response(), EmbedResponse.getDefaultInstance());
    final EmbedResponse actual = EmbedResponse.parseFrom(expected.toByteArray());
    final double tolerance = Double.parseDouble(loadManifest().getProperty("float_tolerance"));

    assertEquals(expected.getVectorsCount(), actual.getVectorsCount());
    for (int vector = 0; vector < expected.getVectorsCount(); vector++) {
      for (int value = 0; value < expected.getVectors(vector).getValuesCount(); value++) {
        final float expectedValue = expected.getVectors(vector).getValues(value);
        final float actualValue = actual.getVectors(vector).getValues(value);
        assertEquals(Float.floatToRawIntBits(expectedValue), Float.floatToRawIntBits(actualValue));
        assertEquals(expectedValue, actualValue, tolerance);
      }
    }
  }

  @Test
  void proto3DefaultsAndMessagePresenceAreExplicit() {
    final ParseDocumentRequest parser = ParseDocumentRequest.getDefaultInstance();
    final DetectRequest detect = DetectRequest.getDefaultInstance();
    final AnonymizeRequest anonymize = AnonymizeRequest.getDefaultInstance();
    final EmbedRequest embed = EmbedRequest.getDefaultInstance();
    final RerankRequest rerank = RerankRequest.getDefaultInstance();

    assertEquals(0, parser.getSerializedSize());
    assertEquals("", parser.getStorageUri());
    assertFalse(parser.hasMetadata());
    assertEquals("", detect.getText());
    assertFalse(detect.hasMetadata());
    assertEquals(DeidPolicy.DEID_POLICY_UNSPECIFIED, anonymize.getPolicy());
    assertEquals(EmbeddingInputType.EMBEDDING_INPUT_TYPE_UNSPECIFIED, embed.getInputType());
    assertTrue(embed.getTextsList().isEmpty());
    assertEquals("", rerank.getQuery());
    assertTrue(rerank.getCandidatesList().isEmpty());

    assertFalse(ParseDocumentResponse.getDefaultInstance().hasError());
    assertFalse(DetectResponse.getDefaultInstance().hasError());
    assertFalse(AnonymizeResponse.getDefaultInstance().hasError());
    assertFalse(EmbedResponse.getDefaultInstance().hasError());
    assertFalse(RerankResponse.getDefaultInstance().hasError());
    assertEquals(
        0,
        EmbedRequest.newBuilder()
            .setInputType(EmbeddingInputType.EMBEDDING_INPUT_TYPE_UNSPECIFIED)
            .setModelName("")
            .build()
            .getSerializedSize());
  }

  @Test
  void responsesHonorClientVisibleSemanticInvariants() throws Exception {
    final FixtureCase parserCase = caseById("parser", "normal-full");
    final ParseDocumentResponse parser =
        (ParseDocumentResponse)
            parse(parserCase.response(), ParseDocumentResponse.getDefaultInstance());
    assertEquals("PARSE_STATUS_SUCCEEDED", parser.getParseStatus().name());
    assertTrue(parser.hasIr());
    assertFalse(parser.hasError());

    final FixtureCase deidCase = caseById("deid", "anonymize-normal");
    final AnonymizeRequest deidRequest =
        (AnonymizeRequest) parse(deidCase.request(), AnonymizeRequest.getDefaultInstance());
    final AnonymizeResponse deidResponse =
        (AnonymizeResponse) parse(deidCase.response(), AnonymizeResponse.getDefaultInstance());
    assertFalse(deidResponse.getText().contains(deidRequest.getText()));
    assertTrue(
        deidResponse.getEntitiesList().stream()
            .allMatch(entity -> entity.getStart() >= 0 && entity.getEnd() > entity.getStart()));

    final FixtureCase embedCase = caseById("embedding", "normal-full");
    final EmbedRequest embedRequest =
        (EmbedRequest) parse(embedCase.request(), EmbedRequest.getDefaultInstance());
    final EmbedResponse embedResponse =
        (EmbedResponse) parse(embedCase.response(), EmbedResponse.getDefaultInstance());
    assertEquals(embedRequest.getTextsCount(), embedResponse.getVectorsCount());
    assertTrue(
        embedResponse.getVectorsList().stream()
            .allMatch(vector -> vector.getValuesCount() == embedResponse.getDimension()));
    assertEquals(embedRequest.getModelName().split("@", 2)[0], embedResponse.getModelName());

    final FixtureCase rerankCase = caseById("rerank", "normal-full");
    final RerankRequest rerankRequest =
        (RerankRequest) parse(rerankCase.request(), RerankRequest.getDefaultInstance());
    final RerankResponse rerankResponse =
        (RerankResponse) parse(rerankCase.response(), RerankResponse.getDefaultInstance());
    assertEquals(rerankRequest.getCandidatesCount(), rerankResponse.getResultsCount());
    for (int index = 0; index < rerankResponse.getResultsCount(); index++) {
      assertEquals(index + 1, rerankResponse.getResults(index).getRank());
      if (index > 0) {
        assertTrue(
            rerankResponse.getResults(index - 1).getScore()
                >= rerankResponse.getResults(index).getScore());
      }
    }
  }

  private static Message parse(final Path path, final Message prototype) throws IOException {
    final Message.Builder builder = prototype.newBuilderForType();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      TextFormat.getParser().merge(reader, builder);
    }
    return builder.build();
  }

  private static Message roundTrip(final Message message) throws IOException {
    return (Message) message.getParserForType().parseFrom(message.toByteArray());
  }

  private static Message prototype(final FixtureCase fixture, final boolean request) {
    return switch (fixture.contract() + ":" + fixture.rpc() + ":" + request) {
      case "parser:ParseDocument:true" -> ParseDocumentRequest.getDefaultInstance();
      case "parser:ParseDocument:false" -> ParseDocumentResponse.getDefaultInstance();
      case "deid:Detect:true" -> DetectRequest.getDefaultInstance();
      case "deid:Detect:false" -> DetectResponse.getDefaultInstance();
      case "deid:Anonymize:true" -> AnonymizeRequest.getDefaultInstance();
      case "deid:Anonymize:false" -> AnonymizeResponse.getDefaultInstance();
      case "embedding:Embed:true" -> EmbedRequest.getDefaultInstance();
      case "embedding:Embed:false" -> EmbedResponse.getDefaultInstance();
      case "rerank:Rerank:true" -> RerankRequest.getDefaultInstance();
      case "rerank:Rerank:false" -> RerankResponse.getDefaultInstance();
      default -> throw new IllegalArgumentException("unknown fixture RPC " + fixture.identity());
    };
  }

  private static String responseErrorCode(final Message response) {
    final FieldDescriptor errorField = response.getDescriptorForType().findFieldByName("error");
    assertTrue(response.hasField(errorField), response.getDescriptorForType().getFullName());
    final Message error = (Message) response.getField(errorField);
    return (String) error.getField(error.getDescriptorForType().findFieldByName("code"));
  }

  private static void collectDescriptorFields(
      final Descriptor descriptor, final Set<String> visitedMessages, final Set<String> fields) {
    if (!visitedMessages.add(descriptor.getFullName())) {
      return;
    }
    for (FieldDescriptor field : descriptor.getFields()) {
      fields.add(field.getFullName());
      if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !field.isMapField()) {
        collectDescriptorFields(field.getMessageType(), visitedMessages, fields);
      }
    }
  }

  private static void collectPopulatedFields(
      final Message message, final Set<String> ancestry, final Set<String> fields) {
    final String messageType = message.getDescriptorForType().getFullName();
    if (!ancestry.add(messageType)) {
      return;
    }
    for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
      final FieldDescriptor field = entry.getKey();
      fields.add(field.getFullName());
      if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE || field.isMapField()) {
        continue;
      }
      if (field.isRepeated()) {
        for (Object value : (List<?>) entry.getValue()) {
          collectPopulatedFields((Message) value, ancestry, fields);
        }
      } else {
        collectPopulatedFields((Message) entry.getValue(), ancestry, fields);
      }
    }
    ancestry.remove(messageType);
  }

  private static FixtureCase caseById(final String contract, final String id) throws IOException {
    return loadCases().stream()
        .filter(fixture -> contract.equals(fixture.contract()) && id.equals(fixture.id()))
        .findFirst()
        .orElseThrow();
  }

  private static List<FixtureCase> loadCases() throws IOException {
    final List<FixtureCase> result = new ArrayList<>();
    final List<String> lines =
        Files.readAllLines(FIXTURES.resolve("cases.tsv"), StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] columns = line.split("\\t", -1);
      assertEquals(10, columns.length, "invalid fixture index row: " + line);
      result.add(
          new FixtureCase(
              columns[0],
              columns[1],
              columns[2],
              columns[3],
              splitValues(columns[4], "\\|"),
              columns[5],
              columns[6],
              FIXTURES.resolve(columns[7]),
              "-".equals(columns[8]) ? null : FIXTURES.resolve(columns[8]),
              columns[9]));
    }
    return result;
  }

  private static Properties loadManifest() throws IOException {
    final Properties properties = new Properties();
    try (Reader reader =
        Files.newBufferedReader(FIXTURES.resolve("manifest.properties"), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  private static Set<String> values(final Properties properties, final String key) {
    return splitValues(properties.getProperty(key, ""), ",");
  }

  private static Set<String> splitValues(final String value, final String separator) {
    if (value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(separator))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private record FixtureCase(
      String contract,
      String rpc,
      String id,
      String category,
      Set<String> boundaries,
      String grpcStatus,
      String errorCode,
      Path request,
      Path response,
      String trigger) {
    boolean hasResponse() {
      return response != null;
    }

    String identity() {
      return contract + "/" + rpc + "/" + id;
    }
  }
}
