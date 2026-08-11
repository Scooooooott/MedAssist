package com.medassist.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.execution")
public record AgentExecutionProperties(int parallelism) {
  public AgentExecutionProperties {
    if (parallelism < 2 || parallelism > 64) {
      throw new IllegalArgumentException("agent.execution.parallelism must be between 2 and 64");
    }
  }

  public static AgentExecutionProperties defaults() {
    return new AgentExecutionProperties(8);
  }
}
