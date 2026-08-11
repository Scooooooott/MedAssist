package com.medassist.ingestion.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationContractTest {
  @Test
  void v2IsAnIncrementalNonDestructiveMigrationFromV1() throws IOException {
    final String v1 = read("V1__m1_baseline_schema.sql");
    final String v2 = read("V2__m1_closeout_m2_foundation.sql");

    assertTrue(v1.contains("CREATE TABLE IF NOT EXISTS document"));
    assertTrue(v1.contains("vector(1024)"));
    assertTrue(v2.contains("ALTER TABLE chunk"));
    assertTrue(v2.contains("CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'WITHDRAWN', 'UNKNOWN'))"));
    assertFalse(
        v2.matches("(?is).*\\b(DROP\\s+TABLE|DROP\\s+COLUMN|TRUNCATE|DELETE\\s+FROM)\\b.*"));
  }

  @Test
  void lexicalIndexesUseOnlyTheSourceFaithfulChunkText() throws IOException {
    final String v2 = read("V2__m1_closeout_m2_foundation.sql");
    final String generatedColumns =
        between(
            v2,
            "ADD COLUMN IF NOT EXISTS lexical_search",
            "CREATE INDEX IF NOT EXISTS ix_chunk_lexical_search");

    assertTrue(generatedColumns.contains("to_tsvector('english', coalesce(text, ''))"));
    assertTrue(generatedColumns.contains("to_tsvector('simple', coalesce(text, ''))"));
    assertFalse(generatedColumns.contains("context_prefix"));
    assertTrue(v2.contains("Generated exclusively from original chunk.text"));
  }

  @Test
  void strategyAndContextDimensionsCannotOverwriteEachOther() throws IOException {
    final String v2 = read("V2__m1_closeout_m2_foundation.sql");
    final String v3 = read("V3__m2_multi_dimension_embeddings.sql");

    assertTrue(v2.contains("ux_chunk_version_strategy_ordinal"));
    assertTrue(v2.contains("ON chunk(document_version_id, chunking_strategy_id, ordinal)"));
    assertTrue(
        v2.contains(
            "UNIQUE (document_version_id, chunking_strategy_id, chunk_ordinal, mode,"
                + " prompt_version)"));
    assertTrue(v2.contains("PRIMARY KEY (chunk_id, model_name, model_version, contextual_mode)"));
    assertTrue(v3.contains("PRIMARY KEY (chunk_id, model_name, model_version, contextual_mode)"));
  }

  @Test
  void dimensionSpecificTablesHaveMatchingHnswIndexes() throws IOException {
    final String v3 = read("V3__m2_multi_dimension_embeddings.sql");
    final String dimension768 = tableDefinition(v3, "chunk_embedding_768");
    final String dimension1536 = tableDefinition(v3, "chunk_embedding_1536");

    assertTrue(dimension768.contains("vector(768)"));
    assertTrue(v3.contains("ix_chunk_embedding_768_hnsw"));
    assertTrue(v3.contains("ON chunk_embedding_768 USING hnsw (embedding vector_cosine_ops)"));
    assertTrue(dimension1536.contains("vector(1536)"));
    assertTrue(v3.contains("ix_chunk_embedding_1536_hnsw"));
    assertTrue(v3.contains("ON chunk_embedding_1536 USING hnsw (embedding vector_cosine_ops)"));
    assertTrue(v3.contains("dimension INTEGER NOT NULL CHECK (dimension IN (768, 1024, 1536))"));
  }

  @Test
  void reviewAndEvaluationTablesStoreSafeMetadataOnly() throws IOException {
    final String v2 = read("V2__m1_closeout_m2_foundation.sql");
    final String review = tableDefinition(v2, "document_metadata_review");
    final String evaluation = tableDefinition(v2, "evaluation_run");
    final String holdout = tableDefinition(v2, "holdout_consumption");

    assertTrue(review.contains("reason_code"));
    assertTrue(review.contains("effective_date"));
    assertTrue(review.contains("resolved_at"));
    assertTrue(evaluation.contains("metrics JSONB NOT NULL CHECK ("));
    assertTrue(evaluation.contains("metrics ?| ARRAY["));
    assertTrue(evaluation.contains("result_uri TEXT"));
    assertTrue(holdout.contains("status TEXT NOT NULL DEFAULT 'RESERVED'"));
    assertTrue(holdout.contains("use_count INTEGER NOT NULL DEFAULT 0"));
    assertTrue(holdout.contains("reuse_bias_note TEXT"));
    assertTrue(holdout.contains("use_count = 1 OR reuse_bias_note IS NOT NULL"));

    assertFalse(
        review.matches("(?is).*\\b(question|answer|source_text|raw_text|prompt)\\s+TEXT.*"));
    assertFalse(
        evaluation.matches("(?is).*\\b(question|answer|source_text|raw_text|prompt)\\s+TEXT.*"));
    assertFalse(
        holdout.matches("(?is).*\\b(question|answer|source_text|raw_text|prompt)\\s+TEXT.*"));
  }

  @Test
  void clinicalImportMigrationStoresOnlySafeHarborRelationsAndAggregateViews() throws IOException {
    final String v6 = read("V6__m3_clinical_safe_harbor_import.sql");

    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_import_run"));
    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_patient"));
    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_encounter"));
    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_condition"));
    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_medication"));
    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_observation"));
    assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS clinical_quarantine"));
    assertTrue(v6.contains("PRIMARY KEY (source_id, resource_id)"));
    assertTrue(v6.contains("CREATE OR REPLACE VIEW clinical_research_condition_counts"));
    assertTrue(v6.contains("CREATE OR REPLACE VIEW clinical_research_observation_counts"));
    assertTrue(v6.contains("CREATE OR REPLACE VIEW clinical_research_encounter_counts"));
    assertTrue(v6.contains("COUNT(DISTINCT (source_id, patient_id))"));
    assertTrue(
        v6.contains(
            "GROUP BY code_system, code, status\nHAVING COUNT(DISTINCT (source_id, patient_id)) >= 5"));
    assertTrue(
        v6.contains(
            "GROUP BY code_system, code, unit, observation_year\nHAVING COUNT(DISTINCT (source_id, patient_id)) >= 5"));
    assertTrue(
        v6.contains(
            "GROUP BY type_system, type_code, start_year\nHAVING COUNT(DISTINCT (source_id, patient_id)) >= 5"));
    assertTrue(v6.contains("zip3 TEXT"));
    assertFalse(
        v6.matches(
            "(?is).*\\b(raw_fhir|original_fhir|payload_json|birth_date|zip_code)\\s+(jsonb|text|date).*"));
  }

  private static String read(final String fileName) throws IOException {
    final String resource = "/db/migration/" + fileName;
    try (InputStream input = MigrationContractTest.class.getResourceAsStream(resource)) {
      assertNotNull(input, "missing migration resource " + resource);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String between(final String value, final String start, final String end) {
    final int startIndex = value.indexOf(start);
    final int endIndex = value.indexOf(end, startIndex + start.length());
    assertTrue(startIndex >= 0 && endIndex > startIndex, "migration section is missing");
    return value.substring(startIndex, endIndex);
  }

  private static String tableDefinition(final String sql, final String tableName) {
    final String marker = "CREATE TABLE IF NOT EXISTS " + tableName;
    final int start = sql.indexOf(marker);
    final int end = sql.indexOf("\n);", start);
    assertTrue(start >= 0 && end > start, "table definition is missing: " + tableName);
    return sql.substring(start, end);
  }
}
