package com.medassist.clinicaldata.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingClinicalQueryAuditSink implements ClinicalQueryAuditSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoggingClinicalQueryAuditSink.class);

  @Override
  public void record(final ResearchQueryAuditEvent event) {
    LOGGER.info(
        "clinical query audit actor={} role={} view={} outcome={} returnedRows={} suppressedGroups={} clinicalExemption={}",
        event.actor(),
        event.role(),
        event.view(),
        event.outcome(),
        event.returnedRows(),
        event.suppressedGroups(),
        event.clinicalExemption());
  }
}
