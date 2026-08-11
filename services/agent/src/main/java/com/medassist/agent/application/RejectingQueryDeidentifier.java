package com.medassist.agent.application;

/** Fail-closed default until the approved deidentification adapter is injected. */
public final class RejectingQueryDeidentifier implements QueryDeidentifier {
  @Override
  public DeidentifiedQuery deidentify(final String rawQuery) {
    throw new DeidentificationException("no deidentification adapter is configured");
  }
}
