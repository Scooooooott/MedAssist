package com.medassist.agent.security;

public enum EgressReason {
  ALLOWED("allowed"),
  NULL_REQUEST("request is missing"),
  UNKNOWN_DESTINATION("destination is unknown"),
  DESTINATION_NOT_ALLOWED("destination is not allowed"),
  UNKNOWN_CONTENT_CLASS("content class is unknown"),
  CONTENT_CLASS_NOT_ALLOWED("content class is not allowed"),
  RAW_USER_QUESTION("raw user question cannot leave the boundary"),
  SENSITIVE_CONTENT("sensitive content cannot leave the boundary"),
  INVALID_PAYLOAD("payload is missing");

  private final String message;

  EgressReason(final String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
