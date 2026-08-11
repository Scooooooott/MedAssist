package com.medassist.clinicaldata.research;

public interface ClinicalQueryAuditSink {
  void record(ResearchQueryAuditEvent event);
}
