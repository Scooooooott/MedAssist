package com.medassist.ingestion.batch.steps;

import com.medassist.ingestion.batch.stage.DiscoveredStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import com.medassist.ingestion.discovery.ObjectDiscoveryService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** Discovers eligible objects and records only restart-safe stage metadata. */
public final class DiscoverObjectsTasklet implements Tasklet {
  private static final String ALL_SCOPE = "all";
  private static final Set<String> SAFE_METADATA_KEYS =
      Set.of("etag", "versionid", "lastmodified", "contenttype", "bucket", "objectkey");

  private final ObjectDiscoveryService discoveryService;
  private final DurableStageRepository stageRepository;

  public DiscoverObjectsTasklet(
      final ObjectDiscoveryService discoveryService, final DurableStageRepository stageRepository) {
    this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
    this.stageRepository = Objects.requireNonNull(stageRepository, "stageRepository");
  }

  @Override
  public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext)
      throws Exception {
    final UUID runId = IngestionStepContext.runId(chunkContext);
    final String sourceScope = sourceScope(chunkContext);
    final boolean forceReprocess = forceReprocess(chunkContext);
    final List<ObjectDiscoveryResult> results = discoveryService.discover(forceReprocess);
    for (final ObjectDiscoveryResult result : results) {
      if (!inScope(result, sourceScope) || !result.processRequired()) {
        continue;
      }
      final UUID logicalDocumentId = logicalDocumentId(result);
      final UUID documentVersionId =
          documentVersionId(logicalDocumentId, result.currentFingerprint());
      stageRepository.upsertDiscovered(
          new DiscoveredStageItem(
              runId,
              logicalDocumentId,
              documentVersionId,
              result.object().storageUri(),
              result.object().sourceId(),
              result.object().mimeType(),
              result.object().size(),
              result.currentFingerprint(),
              result.previousFingerprint().orElse(null),
              result.classification(),
              safeMetadata(result.object().metadata()),
              forceReprocess));
      contribution.incrementReadCount();
      contribution.incrementWriteCount(1);
    }
    return RepeatStatus.FINISHED;
  }

  private static boolean inScope(final ObjectDiscoveryResult result, final String sourceScope) {
    return ALL_SCOPE.equals(sourceScope) || sourceScope.equals(result.object().sourceId());
  }

  private static UUID logicalDocumentId(final ObjectDiscoveryResult result) {
    final String identity = result.object().sourceId() + "\n" + result.object().storageUri();
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
  }

  private static UUID documentVersionId(final UUID logicalDocumentId, final String fingerprint) {
    return UUID.nameUUIDFromBytes(
        (logicalDocumentId + "\n" + fingerprint.toLowerCase()).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, String> safeMetadata(final Map<String, String> metadata) {
    return metadata.entrySet().stream()
        .filter(entry -> SAFE_METADATA_KEYS.contains(normalize(entry.getKey())))
        .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
  }

  private static String sourceScope(final ChunkContext context) {
    final Object parameter = context.getStepContext().getJobParameters().get("sourceScope");
    final String value = parameter == null ? null : parameter.toString();
    return value == null || value.isBlank() ? ALL_SCOPE : value.trim();
  }

  private static boolean forceReprocess(final ChunkContext context) {
    final Object parameter = context.getStepContext().getJobParameters().get("forceReprocess");
    return Boolean.parseBoolean(parameter == null ? "false" : parameter.toString());
  }
}
