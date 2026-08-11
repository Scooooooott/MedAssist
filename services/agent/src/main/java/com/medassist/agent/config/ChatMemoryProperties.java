package com.medassist.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.chat-memory")
public record ChatMemoryProperties(int maxMessages, int maxCharacters) {
  public ChatMemoryProperties {
    if (maxMessages < 2 || maxMessages > 100 || maxCharacters < 256 || maxCharacters > 100_000) {
      throw new IllegalArgumentException("agent.chat-memory limits are outside the safe range");
    }
  }

  public static ChatMemoryProperties defaults() {
    return new ChatMemoryProperties(10, 16_000);
  }
}
