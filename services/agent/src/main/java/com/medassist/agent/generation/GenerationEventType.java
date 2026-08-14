package com.medassist.agent.generation;

public enum GenerationEventType {
  ACCEPTED("accepted"),
  DELTA("delta"),
  CITATION("citation"),
  DEGRADATION("degradation"),
  FINAL("final"),
  ERROR("error"),
  CANCELLED("cancelled");

  private final String wireName;

  GenerationEventType(final String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static GenerationEventType fromWireName(final String value) {
    for (final GenerationEventType type : values()) {
      if (type.wireName.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown generation event type");
  }
}
