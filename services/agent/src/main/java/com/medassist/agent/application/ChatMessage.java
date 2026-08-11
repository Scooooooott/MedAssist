package com.medassist.agent.application;

/** A deidentified conversation message used only while constructing an LLM request. */
public record ChatMessage(String role, String content) {
  public ChatMessage {
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("message role is required");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("message content is required");
    }
    role = role.trim().toLowerCase(java.util.Locale.ROOT);
    if (!role.equals("user") && !role.equals("assistant")) {
      throw new IllegalArgumentException("message role must be user or assistant");
    }
  }
}
