package com.medassist.agent.config;

import com.medassist.agent.llm.routing.LlmBudgetLimits;
import com.medassist.agent.llm.routing.LlmProviderDefinition;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.llm.routing")
public class LlmRoutingProperties {
  private boolean enabled;
  private Duration maxRetryAfter = Duration.ofSeconds(5);
  private List<String> route = new ArrayList<>();
  private Map<String, Provider> providers = new LinkedHashMap<>();
  private Budget budget = new Budget();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getMaxRetryAfter() {
    return maxRetryAfter;
  }

  public void setMaxRetryAfter(final Duration maxRetryAfter) {
    this.maxRetryAfter = maxRetryAfter;
  }

  public List<String> getRoute() {
    return List.copyOf(route);
  }

  public void setRoute(final List<String> route) {
    this.route = new ArrayList<>(route);
  }

  public Map<String, Provider> getProviders() {
    return Map.copyOf(providers);
  }

  public void setProviders(final Map<String, Provider> providers) {
    this.providers = new LinkedHashMap<>(providers);
  }

  public Budget getBudget() {
    return budget;
  }

  public void setBudget(final Budget budget) {
    this.budget = budget;
  }

  public List<LlmProviderDefinition> definitions() {
    return route.stream()
        .map(
            id -> {
              final Provider provider = providers.get(id);
              if (provider == null) {
                throw new IllegalArgumentException("LLM route references an unknown provider");
              }
              return provider.definition(id);
            })
        .toList();
  }

  public LlmBudgetLimits budgetLimits() {
    return budget.limits();
  }

  public static class Provider {
    private URI endpoint;
    private String apiKey = "";
    private String model;
    private String destination = "EXTERNAL_LLM";
    private Duration firstTokenTimeout = Duration.ofSeconds(5);
    private Duration overallTimeout = Duration.ofSeconds(30);
    private BigDecimal inputCostPer1kTokens = BigDecimal.ZERO;
    private BigDecimal outputCostPer1kTokens = BigDecimal.ZERO;
    private int requestsPerMinute = 60;
    private int maxOutputTokens = 1024;

    public URI getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(final URI endpoint) {
      this.endpoint = endpoint;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(final String apiKey) {
      this.apiKey = apiKey;
    }

    public String getModel() {
      return model;
    }

    public void setModel(final String model) {
      this.model = model;
    }

    public String getDestination() {
      return destination;
    }

    public void setDestination(final String destination) {
      this.destination = destination;
    }

    public Duration getFirstTokenTimeout() {
      return firstTokenTimeout;
    }

    public void setFirstTokenTimeout(final Duration firstTokenTimeout) {
      this.firstTokenTimeout = firstTokenTimeout;
    }

    public Duration getOverallTimeout() {
      return overallTimeout;
    }

    public void setOverallTimeout(final Duration overallTimeout) {
      this.overallTimeout = overallTimeout;
    }

    public BigDecimal getInputCostPer1kTokens() {
      return inputCostPer1kTokens;
    }

    public void setInputCostPer1kTokens(final BigDecimal inputCostPer1kTokens) {
      this.inputCostPer1kTokens = inputCostPer1kTokens;
    }

    public BigDecimal getOutputCostPer1kTokens() {
      return outputCostPer1kTokens;
    }

    public void setOutputCostPer1kTokens(final BigDecimal outputCostPer1kTokens) {
      this.outputCostPer1kTokens = outputCostPer1kTokens;
    }

    public int getRequestsPerMinute() {
      return requestsPerMinute;
    }

    public void setRequestsPerMinute(final int requestsPerMinute) {
      this.requestsPerMinute = requestsPerMinute;
    }

    public int getMaxOutputTokens() {
      return maxOutputTokens;
    }

    public void setMaxOutputTokens(final int maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
    }

    LlmProviderDefinition definition(final String id) {
      return new LlmProviderDefinition(
          id,
          endpoint,
          apiKey,
          model,
          destination,
          firstTokenTimeout,
          overallTimeout,
          inputCostPer1kTokens,
          outputCostPer1kTokens,
          requestsPerMinute,
          maxOutputTokens);
    }
  }

  public static class Budget {
    private BigDecimal daily = BigDecimal.valueOf(25);
    private BigDecimal monthly = BigDecimal.valueOf(500);
    private BigDecimal softThreshold = new BigDecimal("0.80");
    private Map<String, BigDecimal> dailyByRole = new LinkedHashMap<>();
    private Map<String, BigDecimal> monthlyByRole = new LinkedHashMap<>();
    private Map<String, BigDecimal> dailyByUser = new LinkedHashMap<>();
    private Map<String, BigDecimal> monthlyByUser = new LinkedHashMap<>();

    public BigDecimal getDaily() {
      return daily;
    }

    public void setDaily(final BigDecimal daily) {
      this.daily = daily;
    }

    public BigDecimal getMonthly() {
      return monthly;
    }

    public void setMonthly(final BigDecimal monthly) {
      this.monthly = monthly;
    }

    public BigDecimal getSoftThreshold() {
      return softThreshold;
    }

    public void setSoftThreshold(final BigDecimal softThreshold) {
      this.softThreshold = softThreshold;
    }

    public Map<String, BigDecimal> getDailyByRole() {
      return Map.copyOf(dailyByRole);
    }

    public void setDailyByRole(final Map<String, BigDecimal> value) {
      dailyByRole = new LinkedHashMap<>(value);
    }

    public Map<String, BigDecimal> getMonthlyByRole() {
      return Map.copyOf(monthlyByRole);
    }

    public void setMonthlyByRole(final Map<String, BigDecimal> value) {
      monthlyByRole = new LinkedHashMap<>(value);
    }

    public Map<String, BigDecimal> getDailyByUser() {
      return Map.copyOf(dailyByUser);
    }

    public void setDailyByUser(final Map<String, BigDecimal> value) {
      dailyByUser = new LinkedHashMap<>(value);
    }

    public Map<String, BigDecimal> getMonthlyByUser() {
      return Map.copyOf(monthlyByUser);
    }

    public void setMonthlyByUser(final Map<String, BigDecimal> value) {
      monthlyByUser = new LinkedHashMap<>(value);
    }

    LlmBudgetLimits limits() {
      return new LlmBudgetLimits(
          daily, monthly, softThreshold, dailyByRole, monthlyByRole, dailyByUser, monthlyByUser);
    }
  }
}
