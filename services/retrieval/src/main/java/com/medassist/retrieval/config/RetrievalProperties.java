package com.medassist.retrieval.config;

import com.medassist.retrieval.application.model.ContextualRetrievalMode;
import com.medassist.retrieval.application.model.RetrievalMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medassist.retrieval")
public class RetrievalProperties {
  private int defaultTopK = 8;
  private int maxTopK = 20;
  private int defaultCandidateTopN = 50;
  private int maxCandidateTopN = 100;
  private int vectorCandidateTopN = 50;
  private int lexicalCandidateTopN = 50;
  private int rrfK = 60;
  private double vectorWeight = 1.0;
  private double lexicalWeight = 1.0;
  private String distanceMetric = "COSINE";
  private String defaultModelName = "bge-m3";
  private String defaultModelVersion = "m1-baseline";
  private Duration llmTimeout = Duration.ofSeconds(30);
  private Duration retrievalTimeout = Duration.ofMillis(500);
  private String abstainMessage =
      "I do not have enough cited evidence in the retrieved corpus to answer safely. "
          + "Please consult a qualified medical professional.";
  private RetrievalMode defaultRetrievalMode = RetrievalMode.HYBRID;
  private boolean defaultRerankEnabled;
  private ContextualRetrievalMode defaultContextualRetrievalMode = ContextualRetrievalMode.OFF;
  private String defaultChunkingStrategyId = "structure-v1";
  private int stalenessYears = 3;
  private boolean legacyAnswerEnabled;
  private ModelService modelService = new ModelService();
  private Llm llm = new Llm();
  private Rerank rerank = new Rerank();
  private Grpc grpc = new Grpc();
  private Database database = new Database();
  private Cache cache = new Cache();

  public int getDefaultTopK() {
    return defaultTopK;
  }

  public void setDefaultTopK(final int defaultTopK) {
    this.defaultTopK = defaultTopK;
  }

  public int getMaxTopK() {
    return maxTopK;
  }

  public int getDefaultCandidateTopN() {
    return defaultCandidateTopN;
  }

  public void setDefaultCandidateTopN(final int defaultCandidateTopN) {
    this.defaultCandidateTopN = defaultCandidateTopN;
  }

  public int getMaxCandidateTopN() {
    return maxCandidateTopN;
  }

  public int getVectorCandidateTopN() {
    return vectorCandidateTopN;
  }

  public void setVectorCandidateTopN(final int vectorCandidateTopN) {
    this.vectorCandidateTopN = vectorCandidateTopN;
  }

  public int getLexicalCandidateTopN() {
    return lexicalCandidateTopN;
  }

  public void setLexicalCandidateTopN(final int lexicalCandidateTopN) {
    this.lexicalCandidateTopN = lexicalCandidateTopN;
  }

  public int getRrfK() {
    return rrfK;
  }

  public void setRrfK(final int rrfK) {
    this.rrfK = rrfK;
  }

  public double getVectorWeight() {
    return vectorWeight;
  }

  public void setVectorWeight(final double vectorWeight) {
    this.vectorWeight = vectorWeight;
  }

  public double getLexicalWeight() {
    return lexicalWeight;
  }

  public void setLexicalWeight(final double lexicalWeight) {
    this.lexicalWeight = lexicalWeight;
  }

  public void setMaxCandidateTopN(final int maxCandidateTopN) {
    this.maxCandidateTopN = maxCandidateTopN;
  }

  public void setMaxTopK(final int maxTopK) {
    this.maxTopK = maxTopK;
  }

  public String getDistanceMetric() {
    return distanceMetric;
  }

  public void setDistanceMetric(final String distanceMetric) {
    this.distanceMetric = distanceMetric;
  }

  public String getDefaultModelName() {
    return defaultModelName;
  }

  public void setDefaultModelName(final String defaultModelName) {
    this.defaultModelName = defaultModelName;
  }

  public String getDefaultModelVersion() {
    return defaultModelVersion;
  }

  public void setDefaultModelVersion(final String defaultModelVersion) {
    this.defaultModelVersion = defaultModelVersion;
  }

  public Duration getLlmTimeout() {
    return llmTimeout;
  }

  public void setLlmTimeout(final Duration llmTimeout) {
    this.llmTimeout = llmTimeout;
  }

  public Duration getRetrievalTimeout() {
    return retrievalTimeout;
  }

  public String getAbstainMessage() {
    return abstainMessage;
  }

  public void setAbstainMessage(final String abstainMessage) {
    this.abstainMessage = abstainMessage;
  }

  public void setRetrievalTimeout(final Duration retrievalTimeout) {
    this.retrievalTimeout = retrievalTimeout;
  }

  public RetrievalMode getDefaultRetrievalMode() {
    return defaultRetrievalMode;
  }

  public void setDefaultRetrievalMode(final RetrievalMode defaultRetrievalMode) {
    this.defaultRetrievalMode = defaultRetrievalMode;
  }

  public boolean isDefaultRerankEnabled() {
    return defaultRerankEnabled;
  }

  public void setDefaultRerankEnabled(final boolean defaultRerankEnabled) {
    this.defaultRerankEnabled = defaultRerankEnabled;
  }

  public ContextualRetrievalMode getDefaultContextualRetrievalMode() {
    return defaultContextualRetrievalMode;
  }

  public void setDefaultContextualRetrievalMode(
      final ContextualRetrievalMode defaultContextualRetrievalMode) {
    this.defaultContextualRetrievalMode = defaultContextualRetrievalMode;
  }

  public String getDefaultChunkingStrategyId() {
    return defaultChunkingStrategyId;
  }

  public void setDefaultChunkingStrategyId(final String defaultChunkingStrategyId) {
    this.defaultChunkingStrategyId = defaultChunkingStrategyId;
  }

  public int getStalenessYears() {
    return stalenessYears;
  }

  public void setStalenessYears(final int stalenessYears) {
    this.stalenessYears = stalenessYears;
  }

  public boolean isLegacyAnswerEnabled() {
    return legacyAnswerEnabled;
  }

  public void setLegacyAnswerEnabled(final boolean legacyAnswerEnabled) {
    this.legacyAnswerEnabled = legacyAnswerEnabled;
  }

  public ModelService getModelService() {
    return modelService;
  }

  public void setModelService(final ModelService modelService) {
    this.modelService = modelService;
  }

  public Llm getLlm() {
    return llm;
  }

  public Grpc getGrpc() {
    return grpc;
  }

  public void setGrpc(final Grpc grpc) {
    this.grpc = grpc;
  }

  public Database getDatabase() {
    return database;
  }

  public void setDatabase(final Database database) {
    this.database = database;
  }

  public Cache getCache() {
    return cache;
  }

  public void setCache(final Cache cache) {
    this.cache = cache;
  }

  public void setLlm(final Llm llm) {
    this.llm = llm;
  }

  public Rerank getRerank() {
    return rerank;
  }

  public void setRerank(final Rerank rerank) {
    this.rerank = rerank;
  }

  public static class ModelService {
    private String endpoint = "";

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(final String endpoint) {
      this.endpoint = endpoint;
    }
  }

  public static class Llm {
    private boolean enabled;
    private String provider = "unconfigured";
    private String model = "unconfigured";
    private String systemPrompt = "classpath:prompts/m1-answer-system.md";
    private double temperature;
    private double inputCostPer1kTokens;
    private double outputCostPer1kTokens;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    public String getProvider() {
      return provider;
    }

    public void setProvider(final String provider) {
      this.provider = provider;
    }

    public String getModel() {
      return model;
    }

    public void setModel(final String model) {
      this.model = model;
    }

    public String getSystemPrompt() {
      return systemPrompt;
    }

    public void setSystemPrompt(final String systemPrompt) {
      this.systemPrompt = systemPrompt;
    }

    public double getTemperature() {
      return temperature;
    }

    public void setTemperature(final double temperature) {
      this.temperature = temperature;
    }

    public double getInputCostPer1kTokens() {
      return inputCostPer1kTokens;
    }

    public void setInputCostPer1kTokens(final double inputCostPer1kTokens) {
      this.inputCostPer1kTokens = inputCostPer1kTokens;
    }

    public double getOutputCostPer1kTokens() {
      return outputCostPer1kTokens;
    }

    public void setOutputCostPer1kTokens(final double outputCostPer1kTokens) {
      this.outputCostPer1kTokens = outputCostPer1kTokens;
    }
  }

  public static class Grpc {
    private boolean enabled = true;
    private int port = 9004;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    public int getPort() {
      return port;
    }

    public void setPort(final int port) {
      this.port = port;
    }
  }

  public static class Rerank {
    private String modelName = "cross-encoder/ms-marco-MiniLM-L-6-v2";
    private Duration timeout = Duration.ofMillis(300);

    public String getModelName() {
      return modelName;
    }

    public void setModelName(final String modelName) {
      this.modelName = modelName;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(final Duration timeout) {
      this.timeout = timeout;
    }
  }

  public static class Database {
    private boolean validateVectorDimension = true;
    private int vectorDimension = 1024;
    private String schema = "public";

    public boolean isValidateVectorDimension() {
      return validateVectorDimension;
    }

    public void setValidateVectorDimension(final boolean validateVectorDimension) {
      this.validateVectorDimension = validateVectorDimension;
    }

    public int getVectorDimension() {
      return vectorDimension;
    }

    public void setVectorDimension(final int vectorDimension) {
      this.vectorDimension = vectorDimension;
    }

    public String getSchema() {
      return schema;
    }

    public void setSchema(final String schema) {
      this.schema = schema;
    }
  }

  public static class Cache {
    private boolean embeddingEnabled;
    private boolean answerEnabled;
    private String adminToken = "";
    private Duration embeddingTtl = Duration.ofDays(7);
    private Duration answerTtl = Duration.ofHours(1);
    private String keyPrefix = "medassist:m2:";

    public boolean isEmbeddingEnabled() {
      return embeddingEnabled;
    }

    public void setEmbeddingEnabled(final boolean embeddingEnabled) {
      this.embeddingEnabled = embeddingEnabled;
    }

    public boolean isAnswerEnabled() {
      return answerEnabled;
    }

    public void setAnswerEnabled(final boolean answerEnabled) {
      this.answerEnabled = answerEnabled;
    }

    public String getAdminToken() {
      return adminToken;
    }

    public void setAdminToken(final String adminToken) {
      this.adminToken = adminToken;
    }

    public Duration getEmbeddingTtl() {
      return embeddingTtl;
    }

    public void setEmbeddingTtl(final Duration embeddingTtl) {
      this.embeddingTtl = embeddingTtl;
    }

    public Duration getAnswerTtl() {
      return answerTtl;
    }

    public void setAnswerTtl(final Duration answerTtl) {
      this.answerTtl = answerTtl;
    }

    public String getKeyPrefix() {
      return keyPrefix;
    }

    public void setKeyPrefix(final String keyPrefix) {
      this.keyPrefix = keyPrefix;
    }
  }
}
