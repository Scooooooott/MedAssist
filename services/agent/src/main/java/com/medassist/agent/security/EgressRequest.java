package com.medassist.agent.security;

public record EgressRequest(
    String destination,
    ContentClass contentClass,
    EgressSource source,
    String payload,
    boolean rawUserQuestion) {
  public EgressRequest(
      final String destination, final ContentClass contentClass, final String payload) {
    this(destination, contentClass, EgressSource.UNKNOWN, payload, false);
  }

  public EgressRequest(
      final String destination,
      final ContentClass contentClass,
      final EgressSource source,
      final String payload) {
    this(destination, contentClass, source, payload, source == EgressSource.USER_QUERY);
  }
}
