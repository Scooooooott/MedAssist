package com.medassist.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.agent.state.AgentRetrievalFilters;
import com.medassist.agent.state.QueryClassification;
import com.medassist.contracts.v1.DocumentMetadata;
import com.medassist.contracts.v1.RetrievalResult;
import com.medassist.contracts.v1.RetrievalServiceGrpc;
import com.medassist.contracts.v1.SearchRequest;
import com.medassist.contracts.v1.SearchResponse;
import com.medassist.contracts.v1.SourceRange;
import com.medassist.domain.Role;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetrievalGrpcToolBackendTest {
  @Test
  void sendsRoleQueryAndRequestMetadataAndMapsResultsSafely() {
    final RetrievalServiceGrpc.RetrievalServiceBlockingStub stub =
        mock(RetrievalServiceGrpc.RetrievalServiceBlockingStub.class);
    final String rawContent = "raw retrieval content must not enter agent state";
    final SearchResponse response =
        SearchResponse.newBuilder()
            .addResults(
                RetrievalResult.newBuilder()
                    .setChunkId("chunk-1")
                    .setDocumentVersionId("version-7")
                    .setSectionPath("section/one")
                    .setText(rawContent)
                    .setSourceRange(SourceRange.newBuilder().setStart(4).setEnd(19))
                    .setScore(0.91)
                    .setSource(
                        DocumentMetadata.newBuilder().setDocType("GUIDELINE").setVersion("v7"))
                    .build())
            .build();
    when(stub.search(any(SearchRequest.class))).thenReturn(response);
    final ToolInvocationRequest request =
        new ToolInvocationRequest(
            "policy_search",
            "deidentified query",
            "sha256:query",
            Role.CLINICIAN,
            QueryClassification.MIXED,
            7,
            new AgentRetrievalFilters(
                Set.of("GUIDELINE"),
                Set.of("WHO"),
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2026-01-01"),
                Set.of("RECOMMENDATION")),
            "trace-1",
            "request-1");

    final ToolBackendResult result = new RetrievalGrpcToolBackend(stub).execute(request);

    final ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
    verify(stub).search(captured.capture());
    final SearchRequest sent = captured.getValue();
    assertEquals("trace-1", sent.getMetadata().getTraceId());
    assertEquals("request-1", sent.getMetadata().getRequestId());
    assertEquals("CLINICIAN", sent.getMetadata().getRole());
    assertEquals("deidentified query", sent.getQuery());
    assertEquals("CLINICIAN", sent.getRole());
    assertEquals(7, sent.getTopK());
    assertEquals(java.util.List.of("GUIDELINE"), sent.getFilters().getDocTypeList());
    assertEquals(java.util.List.of("WHO"), sent.getFilters().getPublisherList());
    assertEquals(java.util.List.of("RECOMMENDATION"), sent.getFilters().getSectionTypeList());
    assertEquals("2025-01-01", sent.getFilters().getEffectiveDateFrom());
    assertEquals("2026-01-01", sent.getFilters().getEffectiveDateTo());
    assertFalse(sent.getIncludeSuperseded());
    assertEquals(1, result.chunks().size());
    assertEquals(rawContent, result.chunks().getFirst().content());
    assertEquals("v7", result.chunks().getFirst().version());
    assertEquals("section/one", result.chunks().getFirst().citationLocator());
    assertFalse(ToolResultProjector.project(result).toString().contains(rawContent));
    assertTrue(result.chunks().getFirst().chunkHash().startsWith("sha256:"));
  }
}
