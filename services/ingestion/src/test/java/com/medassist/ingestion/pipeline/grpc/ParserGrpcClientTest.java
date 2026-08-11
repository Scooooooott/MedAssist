package com.medassist.ingestion.pipeline.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.contracts.v1.DocumentIR;
import com.medassist.contracts.v1.ParseDocumentResponse;
import com.medassist.contracts.v1.ParseStatus;
import com.medassist.contracts.v1.ParserServiceGrpc;
import com.medassist.contracts.v1.Section;
import com.medassist.contracts.v1.SourceRange;
import com.medassist.contracts.v1.TableBlock;
import com.medassist.contracts.v1.TableRow;
import com.medassist.ingestion.pipeline.parse.ParserException;
import com.medassist.ingestion.pipeline.parse.ParserPermanentException;
import com.medassist.ingestion.pipeline.parse.ParserRequest;
import com.medassist.ingestion.pipeline.parse.ParserResponse;
import com.medassist.ingestion.pipeline.parse.ParserTransientException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ParserGrpcClientTest {
  @Test
  void appliesRequestDeadlineAndMapsCompleteRecursiveDocument() throws ParserException {
    final ParserServiceGrpc.ParserServiceBlockingStub stub =
        org.mockito.Mockito.mock(ParserServiceGrpc.ParserServiceBlockingStub.class);
    final ParserServiceGrpc.ParserServiceBlockingStub timedStub =
        org.mockito.Mockito.mock(ParserServiceGrpc.ParserServiceBlockingStub.class);
    when(stub.withDeadlineAfter(eq(Duration.ofMillis(250).toNanos()), eq(TimeUnit.NANOSECONDS)))
        .thenReturn(timedStub);
    when(timedStub.parseDocument(any())).thenReturn(response());

    final ParserResponse result =
        new ParserGrpcClient(stub)
            .parse(
                new ParserRequest(
                    URI.create("s3://bucket/object"),
                    "application/pdf",
                    "source-1",
                    Map.of("profile", "synthetic"),
                    Duration.ofMillis(250)));

    verify(stub).withDeadlineAfter(Duration.ofMillis(250).toNanos(), TimeUnit.NANOSECONDS);
    assertEquals(com.medassist.ingestion.pipeline.parse.ParseStatus.SUCCEEDED, result.status());
    assertEquals(Map.of("title", "Synthetic"), result.document().metadata());
    assertEquals("child", result.document().sections().get(0).children().get(0).text());
    assertEquals(8, result.document().sections().get(0).children().get(0).sourceRange().end());
    assertEquals("table text", result.document().tables().get(0).linearizedText());
    assertEquals("value", result.document().tables().get(0).rows().get(0).get("header"));
  }

  @Test
  void classifiesUnavailableAsTransient() {
    final ParserServiceGrpc.ParserServiceBlockingStub stub =
        org.mockito.Mockito.mock(ParserServiceGrpc.ParserServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS))).thenReturn(stub);
    when(stub.parseDocument(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    assertInstanceOf(
        ParserTransientException.class,
        assertThrows(
            ParserTransientException.class, () -> new ParserGrpcClient(stub).parse(request())));
  }

  @Test
  void classifiesInvalidArgumentAsPermanentWithoutStatusDescription() {
    final ParserServiceGrpc.ParserServiceBlockingStub stub =
        org.mockito.Mockito.mock(ParserServiceGrpc.ParserServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS))).thenReturn(stub);
    when(stub.parseDocument(any()))
        .thenThrow(
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("not copied")));

    final ParserPermanentException exception =
        assertThrows(
            ParserPermanentException.class, () -> new ParserGrpcClient(stub).parse(request()));
    assertEquals("parser gRPC failure: INVALID_ARGUMENT", exception.getMessage());
  }

  private static ParserRequest request() {
    return new ParserRequest(
        URI.create("s3://bucket/object"),
        "application/pdf",
        "source-1",
        Map.of(),
        Duration.ofSeconds(1));
  }

  private static ParseDocumentResponse response() {
    final Section child =
        Section.newBuilder()
            .setPath("1.1")
            .setHeading("Child")
            .setLevel(2)
            .setText("child")
            .setSourceRange(SourceRange.newBuilder().setStart(4).setEnd(8).build())
            .build();
    final Section root =
        Section.newBuilder()
            .setPath("1")
            .setHeading("Root")
            .setLevel(1)
            .setText("root")
            .addChildren(child)
            .setSourceRange(SourceRange.newBuilder().setStart(0).setEnd(8).build())
            .build();
    final TableBlock table =
        TableBlock.newBuilder()
            .setSectionPath("1")
            .setCaption("Caption")
            .addHeaders("header")
            .addRows(TableRow.newBuilder().putCells("header", "value").build())
            .setLinearizedText("table text")
            .setSourceRange(SourceRange.newBuilder().setStart(9).setEnd(19).build())
            .build();
    return ParseDocumentResponse.newBuilder()
        .setIr(
            DocumentIR.newBuilder()
                .addSections(root)
                .addTables(table)
                .putMetadata("title", "Synthetic")
                .build())
        .setParseStatus(ParseStatus.PARSE_STATUS_SUCCEEDED)
        .addWarnings("synthetic warning")
        .build();
  }
}
