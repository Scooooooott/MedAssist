package com.medassist.clinicaldata.research;

public class ResearchQueryAccessDeniedException extends RuntimeException {
  public ResearchQueryAccessDeniedException(final String message) {
    super(message);
  }
}
