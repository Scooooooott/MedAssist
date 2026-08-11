package com.medassist.ingestion.config;

import com.medassist.ingestion.context.ContextualRetrievalMode;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medassist.ingestion")
public class IngestionProperties implements InitializingBean {
  private int embeddingBatchSize = 32;
  private int vectorDimension = 1024;
  private int skipLimit = 20;
  private int retryMaxAttempts = 3;
  private Duration retryInitialBackoff = Duration.ofMillis(250);
  private Duration retryMaxBackoff = Duration.ofSeconds(2);
  private double retryMultiplier = 2.0;
  private String defaultSourceSystem = "minio";
  private String defaultDocType = "UNKNOWN";
  private String defaultContentDomain = "PUBLIC";
  private Duration parserTimeout = Duration.ofSeconds(120);
  private Duration deidTimeout = Duration.ofSeconds(120);
  private Duration modelTimeout = Duration.ofSeconds(120);
  private Duration phiScanTimeout = Duration.ofSeconds(120);
  private String deidentificationPolicy = "SAFE_HARBOR_SURROGATE";
  private EmbeddingModel embeddingModel = new EmbeddingModel();
  private Context context = new Context();
  private Minio minio = new Minio();
  private Services services = new Services();

  public int getEmbeddingBatchSize() {
    return embeddingBatchSize;
  }

  public void setEmbeddingBatchSize(final int embeddingBatchSize) {
    this.embeddingBatchSize = embeddingBatchSize;
  }

  public int getVectorDimension() {
    return vectorDimension;
  }

  public void setVectorDimension(final int vectorDimension) {
    this.vectorDimension = vectorDimension;
  }

  public int getSkipLimit() {
    return skipLimit;
  }

  public void setSkipLimit(final int skipLimit) {
    this.skipLimit = skipLimit;
  }

  public int getRetryMaxAttempts() {
    return retryMaxAttempts;
  }

  public void setRetryMaxAttempts(final int retryMaxAttempts) {
    this.retryMaxAttempts = retryMaxAttempts;
  }

  public Duration getRetryInitialBackoff() {
    return retryInitialBackoff;
  }

  public void setRetryInitialBackoff(final Duration retryInitialBackoff) {
    this.retryInitialBackoff = retryInitialBackoff;
  }

  public Duration getRetryMaxBackoff() {
    return retryMaxBackoff;
  }

  public void setRetryMaxBackoff(final Duration retryMaxBackoff) {
    this.retryMaxBackoff = retryMaxBackoff;
  }

  public double getRetryMultiplier() {
    return retryMultiplier;
  }

  public void setRetryMultiplier(final double retryMultiplier) {
    this.retryMultiplier = retryMultiplier;
  }

  public String getDefaultSourceSystem() {
    return defaultSourceSystem;
  }

  public void setDefaultSourceSystem(final String defaultSourceSystem) {
    this.defaultSourceSystem = defaultSourceSystem;
  }

  public String getDefaultDocType() {
    return defaultDocType;
  }

  public void setDefaultDocType(final String defaultDocType) {
    this.defaultDocType = defaultDocType;
  }

  public String getDefaultContentDomain() {
    return defaultContentDomain;
  }

  public void setDefaultContentDomain(final String defaultContentDomain) {
    this.defaultContentDomain = defaultContentDomain;
  }

  public Duration getParserTimeout() {
    return parserTimeout;
  }

  public void setParserTimeout(final Duration parserTimeout) {
    this.parserTimeout = parserTimeout;
  }

  public Duration getDeidTimeout() {
    return deidTimeout;
  }

  public void setDeidTimeout(final Duration deidTimeout) {
    this.deidTimeout = deidTimeout;
  }

  public Duration getModelTimeout() {
    return modelTimeout;
  }

  public void setModelTimeout(final Duration modelTimeout) {
    this.modelTimeout = modelTimeout;
  }

  public Duration getPhiScanTimeout() {
    return phiScanTimeout;
  }

  public void setPhiScanTimeout(final Duration phiScanTimeout) {
    this.phiScanTimeout = phiScanTimeout;
  }

  public String getDeidentificationPolicy() {
    return deidentificationPolicy;
  }

  public void setDeidentificationPolicy(final String deidentificationPolicy) {
    this.deidentificationPolicy = deidentificationPolicy;
  }

  public EmbeddingModel getEmbeddingModel() {
    return embeddingModel;
  }

  public void setEmbeddingModel(final EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  public Context getContext() {
    return context;
  }

  public void setContext(final Context context) {
    this.context = context;
  }

  public Minio getMinio() {
    return minio;
  }

  public void setMinio(final Minio minio) {
    this.minio = minio;
  }

  public Services getServices() {
    return services;
  }

  public void setServices(final Services services) {
    this.services = services;
  }

  @Override
  public void afterPropertiesSet() {
    requirePositive(embeddingBatchSize, "embeddingBatchSize");
    requirePositive(vectorDimension, "vectorDimension");
    requireNonNegative(skipLimit, "skipLimit");
    requirePositive(retryMaxAttempts, "retryMaxAttempts");
    requirePositive(retryInitialBackoff, "retryInitialBackoff");
    requirePositive(retryMaxBackoff, "retryMaxBackoff");
    if (retryMaxBackoff.compareTo(retryInitialBackoff) < 0 || retryMultiplier <= 1.0) {
      throw new IllegalArgumentException("retry backoff settings are invalid");
    }
    requireText(defaultSourceSystem, "defaultSourceSystem");
    requireText(defaultDocType, "defaultDocType");
    requireText(defaultContentDomain, "defaultContentDomain");
    requirePositive(parserTimeout, "parserTimeout");
    requirePositive(deidTimeout, "deidTimeout");
    requirePositive(modelTimeout, "modelTimeout");
    requirePositive(phiScanTimeout, "phiScanTimeout");
    requireText(deidentificationPolicy, "deidentificationPolicy");
    if (vectorDimension != embeddingModel.dimension) {
      throw new IllegalArgumentException("vectorDimension must match embeddingModel.dimension");
    }
    embeddingModel.validate();
    context.validate();
    minio.validate();
    services.validate();
  }

  private static void requirePositive(final int value, final String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNegative(final int value, final String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }

  private static void requirePositive(final Duration value, final String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  public static class EmbeddingModel {
    private String name = "bge-m3";
    private String version = "m1-baseline";
    private int dimension = 1024;

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(final String version) {
      this.version = version;
    }

    public int getDimension() {
      return dimension;
    }

    public void setDimension(final int dimension) {
      this.dimension = dimension;
    }

    private void validate() {
      requireText(name, "embeddingModel.name");
      requireText(version, "embeddingModel.version");
      requirePositive(dimension, "embeddingModel.dimension");
    }
  }

  public static class Context {
    private ContextualRetrievalMode mode = ContextualRetrievalMode.OFF;
    private String promptVersion = "context-v1";
    private int backfillChunkLimit = 500;
    private Path approvedCostArtifact = Path.of("config", "context-cost-not-approved.json");
    private String approvedCostArtifactSha256 = "0".repeat(64);

    public ContextualRetrievalMode getMode() {
      return mode;
    }

    public void setMode(final ContextualRetrievalMode mode) {
      this.mode = mode;
    }

    public String getPromptVersion() {
      return promptVersion;
    }

    public void setPromptVersion(final String promptVersion) {
      this.promptVersion = promptVersion;
    }

    public int getBackfillChunkLimit() {
      return backfillChunkLimit;
    }

    public void setBackfillChunkLimit(final int backfillChunkLimit) {
      this.backfillChunkLimit = backfillChunkLimit;
    }

    public Path getApprovedCostArtifact() {
      return approvedCostArtifact;
    }

    public void setApprovedCostArtifact(final Path approvedCostArtifact) {
      this.approvedCostArtifact = approvedCostArtifact;
    }

    public String getApprovedCostArtifactSha256() {
      return approvedCostArtifactSha256;
    }

    public void setApprovedCostArtifactSha256(final String approvedCostArtifactSha256) {
      this.approvedCostArtifactSha256 = approvedCostArtifactSha256;
    }

    private void validate() {
      if (mode == null) {
        throw new IllegalArgumentException("context.mode must not be null");
      }
      requireText(promptVersion, "context.promptVersion");
      requirePositive(backfillChunkLimit, "context.backfillChunkLimit");
      if (approvedCostArtifact == null) {
        throw new IllegalArgumentException("context.approvedCostArtifact must not be null");
      }
      if (approvedCostArtifactSha256 == null
          || !approvedCostArtifactSha256.matches("[0-9a-fA-F]{64}")) {
        throw new IllegalArgumentException(
            "context.approvedCostArtifactSha256 must be a 64-character hexadecimal hash");
      }
    }
  }

  public static class Minio {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "raw-documents";

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(final String endpoint) {
      this.endpoint = endpoint;
    }

    public String getAccessKey() {
      return accessKey;
    }

    public void setAccessKey(final String accessKey) {
      this.accessKey = accessKey;
    }

    public String getSecretKey() {
      return secretKey;
    }

    public void setSecretKey(final String secretKey) {
      this.secretKey = secretKey;
    }

    public String getBucket() {
      return bucket;
    }

    public void setBucket(final String bucket) {
      this.bucket = bucket;
    }

    private void validate() {
      requireText(endpoint, "minio.endpoint");
      requireText(bucket, "minio.bucket");
    }
  }

  public static class Services {
    private String parserEndpoint = "localhost:9001";
    private String deidEndpoint = "localhost:9002";
    private String modelEndpoint = "localhost:9003";

    public String getParserEndpoint() {
      return parserEndpoint;
    }

    public void setParserEndpoint(final String parserEndpoint) {
      this.parserEndpoint = parserEndpoint;
    }

    public String getDeidEndpoint() {
      return deidEndpoint;
    }

    public void setDeidEndpoint(final String deidEndpoint) {
      this.deidEndpoint = deidEndpoint;
    }

    public String getModelEndpoint() {
      return modelEndpoint;
    }

    public void setModelEndpoint(final String modelEndpoint) {
      this.modelEndpoint = modelEndpoint;
    }

    private void validate() {
      requireText(parserEndpoint, "services.parserEndpoint");
      requireText(deidEndpoint, "services.deidEndpoint");
      requireText(modelEndpoint, "services.modelEndpoint");
    }
  }
}
