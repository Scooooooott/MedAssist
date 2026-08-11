package com.medassist.ingestion.pipeline.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.domain.DocumentIR;
import com.medassist.domain.PhiEntity;
import com.medassist.domain.Section;
import com.medassist.domain.SourceRange;
import com.medassist.domain.TableBlock;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.discovery.ObjectDescriptor;
import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import com.medassist.ingestion.pipeline.mapping.SourceRangeMap;
import com.medassist.ingestion.pipeline.model.FailureStage;
import com.medassist.ingestion.pipeline.model.IngestionWorkItem;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ParseAndDeidentifyProcessorTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(4);

  @Test
  void parsesBeforeCallingDeidentificationAndPassesTimeout() {
    final List<String> calls = new ArrayList<>();
    final AtomicReference<Duration> receivedTimeout = new AtomicReference<>();
    final AtomicReference<String> receivedDocumentKey = new AtomicReference<>();
    final ParserClient parser =
        request -> {
          calls.add("parse");
          assertEquals(TIMEOUT, request.timeout());
          return new ParserResponse(document(), ParseStatus.SUCCEEDED, List.of());
        };
    final DeidentificationClient deid =
        request -> {
          calls.add("deid");
          receivedTimeout.set(request.timeout());
          receivedDocumentKey.set(request.documentKey());
          return response(request.text());
        };

    final ParseAndDeidentifyState result = processor(parser, deid).process(workItem());

    assertEquals(ProcessingStatus.SUCCEEDED, result.status());
    assertEquals("parse", calls.get(0));
    assertTrue(calls.subList(1, calls.size()).stream().allMatch("deid"::equals));
    assertEquals(TIMEOUT, receivedTimeout.get());
    assertEquals(result.documentVersionId().toString(), receivedDocumentKey.get());
  }

  @Test
  void preservesNestedStructureTableShapeAndSourceRanges() {
    final List<String> inputs = new ArrayList<>();
    final DeidentificationClient deid =
        request -> {
          inputs.add(request.text());
          return new DeidentificationResponse(
              mapped(request.text()),
              List.of(new PhiEntity("PERSON", 0, 1, 0.99D, "test")),
              "policy-v1");
        };

    final ParseAndDeidentifyState result =
        processor(request -> new ParserResponse(document(), ParseStatus.SUCCEEDED, List.of()), deid)
            .process(workItem());

    final Section original = document().sections().get(0);
    final Section transformed = result.deidentifiedDocument().sections().get(0);
    assertEquals(original.path(), transformed.path());
    assertEquals("Xection", transformed.heading());
    assertEquals(original.level(), transformed.level());
    assertEquals(original.sourceRange(), transformed.sourceRange());
    assertEquals("Xection-text", transformed.text());
    assertEquals(
        original.children().get(0).sourceRange(), transformed.children().get(0).sourceRange());
    assertEquals("Xhild-text", transformed.children().get(0).text());

    final TableBlock originalTable = document().tables().get(0);
    final TableBlock transformedTable = result.deidentifiedDocument().tables().get(0);
    assertEquals(originalTable.sectionPath(), transformedTable.sectionPath());
    assertEquals("Xalues", transformedTable.caption());
    assertEquals(List.of("Xame", "Xalue"), transformedTable.headers());
    assertEquals(originalTable.sourceRange(), transformedTable.sourceRange());
    assertEquals("Xell-text", transformedTable.rows().get(0).get("Xame"));
    assertEquals("Xinearized-text", transformedTable.linearizedText());
    assertTrue(
        inputs.containsAll(
            List.of(
                "Section",
                "section-text",
                "Child",
                "child-text",
                "Values",
                "Name",
                "Value",
                "cell-text",
                "value-text",
                "linearized-text",
                "Synthetic")));
    assertEquals(11, inputs.size());
    assertEquals(11, result.phiTypeCounts().get("PERSON"));
    assertEquals("Xynthetic", result.deidentifiedDocument().metadata().get("title"));
    assertTrue(
        result.deidentifiedDocument().metadata().keySet().stream()
            .anyMatch(key -> key.startsWith("__medassist.source-range.")));
  }

  @Test
  void propagatesPartialWarningsWithoutChangingSuccessfulOutput() {
    final List<String> warnings = List.of("one section was incomplete", "table layout fallback");
    final ParseAndDeidentifyState result =
        processor(
                request -> new ParserResponse(document(), ParseStatus.PARTIAL, warnings),
                request -> response(request.text()))
            .process(workItem());

    assertEquals(ProcessingStatus.PARTIAL, result.status());
    assertEquals(warnings, result.warnings());
    assertFalse(result.isQuarantined());
    assertEquals("policy-v1", result.policyVersion());
  }

  @Test
  void deidentificationFailureIsQuarantineSafeAndRedactsExceptionMessage() {
    final String rawMessage = "RAW_IDENTIFIER_TOKEN";
    final ParseAndDeidentifyState result =
        processor(
                request -> new ParserResponse(document(), ParseStatus.SUCCEEDED, List.of()),
                request -> {
                  throw new DeidentificationPermanentException(rawMessage);
                })
            .process(workItem());

    assertEquals(ProcessingStatus.QUARANTINED, result.status());
    assertEquals(FailureStage.DEIDENTIFICATION, result.failureStage());
    assertNull(result.deidentifiedDocument());
    assertTrue(result.failureReason().contains("de-identification"));
    assertFalse(result.failureReason().contains(rawMessage));
    assertTrue(result.phiTypeCounts().isEmpty());
    assertTrue(result.policyVersion().isEmpty());
  }

  @Test
  void rejectsFailedAndEmptyParseResultsBeforeDeidentification() {
    final AtomicReference<Boolean> called = new AtomicReference<>(false);
    final DeidentificationClient deid =
        request -> {
          called.set(true);
          return response(request.text());
        };

    final ParseAndDeidentifyState failed =
        processor(
                request -> new ParserResponse(null, ParseStatus.FAILED, List.of("parse failed")),
                deid)
            .process(workItem());
    final ParseAndDeidentifyState empty =
        processor(
                request ->
                    new ParserResponse(
                        new DocumentIR(List.of(), List.of(), Map.of()),
                        ParseStatus.SUCCEEDED,
                        List.of()),
                deid)
            .process(workItem());

    assertEquals(FailureStage.PARSE, failed.failureStage());
    assertEquals("parser reported failure", failed.failureReason());
    assertEquals(FailureStage.PARSE, empty.failureStage());
    assertEquals("parser returned empty document", empty.failureReason());
    assertFalse(called.get());
  }

  private static ParseAndDeidentifyProcessor processor(
      final ParserClient parser, final DeidentificationClient deid) {
    return new ParseAndDeidentifyProcessor(parser, deid, TIMEOUT, "SAFE_HARBOR_REDACT");
  }

  private static DeidentificationResponse response(final String text) {
    return new DeidentificationResponse(
        mapped(text),
        text.isEmpty() ? List.of() : List.of(new PhiEntity("PERSON", 0, 1, 0.99D, "test")),
        "policy-v1");
  }

  @Test
  void mapsLengthChangingReplacementToOriginalDocumentRange() {
    final DocumentIR original =
        new DocumentIR(
            List.of(
                new Section("1", "", 1, "John was seen.", List.of(), new SourceRange(100, 114))),
            List.of(),
            Map.of());
    final ParseAndDeidentifyState result =
        processor(
                request -> new ParserResponse(original, ParseStatus.SUCCEEDED, List.of()),
                request -> {
                  if (request.text().equals("John was seen.")) {
                    return new DeidentificationResponse(
                        "<PERSON> was seen.",
                        List.of(new PhiEntity("PERSON", 0, 4, 0.99D, "test")),
                        "policy-v1");
                  }
                  return response(request.text());
                })
            .process(workItem());

    assertEquals(ProcessingStatus.SUCCEEDED, result.status());
    final String encoded =
        result
            .deidentifiedDocument()
            .metadata()
            .get(SourceRangeMap.metadataKey(SourceRangeMap.sectionTextField("1")));
    assertEquals(
        new SourceRange(100, 114),
        SourceRangeMap.parse(encoded).rangeFor(0, "<PERSON> was seen.".length()));
  }

  @Test
  void leavesGeneratedTableTextOnTheTableRangeFallback() {
    final TableBlock table =
        new TableBlock(
            "1",
            "Findings",
            List.of("Name", "Value"),
            List.of(Map.of("Name", "Alice", "Value", "Stable")),
            "",
            new SourceRange(40, 60));
    final ParseAndDeidentifyState result =
        processor(
                request ->
                    new ParserResponse(
                        new DocumentIR(List.of(), List.of(table), Map.of()),
                        ParseStatus.SUCCEEDED,
                        List.of()),
                request -> response(request.text()))
            .process(workItem());

    assertEquals(ProcessingStatus.SUCCEEDED, result.status());
    assertTrue(
        result.deidentifiedDocument().metadata().keySet().stream()
            .noneMatch(key -> key.startsWith("__medassist.source-range.table-text:")));
  }

  @Test
  void quarantinesWhenDeidentificationChangesTextOutsideDetectedEntity() {
    final ParseAndDeidentifyState result =
        processor(
                request -> new ParserResponse(document(), ParseStatus.SUCCEEDED, List.of()),
                request ->
                    new DeidentificationResponse(
                        mapped(request.text()) + "unexpected-suffix",
                        request.text().isEmpty()
                            ? List.of()
                            : List.of(new PhiEntity("PERSON", 0, 1, 0.99D, "test")),
                        "policy-v1"))
            .process(workItem());

    assertEquals(ProcessingStatus.QUARANTINED, result.status());
    assertEquals(FailureStage.DEIDENTIFICATION, result.failureStage());
    assertEquals("de-identification permanent failure", result.failureReason());
  }

  private static String mapped(final String text) {
    return text.isEmpty() ? text : "X" + text.substring(1);
  }

  private static IngestionWorkItem workItem() {
    final ObjectDescriptor descriptor =
        new ObjectDescriptor(
            URI.create("s3://raw/source-1"),
            "source-1",
            "text/plain",
            10,
            Map.of("tenant", "synthetic"),
            () -> new ByteArrayInputStream("synthetic".getBytes(StandardCharsets.UTF_8)));
    final ObjectDiscoveryResult discovery =
        new ObjectDiscoveryResult(
            descriptor, "hash-1", Optional.empty(), DiscoveryClassification.NEW, true);
    return new IngestionWorkItem(discovery, UUID.randomUUID(), UUID.randomUUID());
  }

  private static DocumentIR document() {
    final Section child =
        new Section("1.1", "Child", 2, "child-text", List.of(), new SourceRange(25, 35));
    final Section section =
        new Section("1", "Section", 1, "section-text", List.of(child), new SourceRange(10, 22));
    final TableBlock table =
        new TableBlock(
            "1",
            "Values",
            List.of("Name", "Value"),
            List.of(Map.of("Name", "cell-text", "Value", "value-text")),
            "linearized-text",
            new SourceRange(40, 60));
    return new DocumentIR(List.of(section), List.of(table), Map.of("title", "Synthetic"));
  }
}
