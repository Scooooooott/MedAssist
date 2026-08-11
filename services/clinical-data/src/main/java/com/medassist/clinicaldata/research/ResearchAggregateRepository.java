package com.medassist.clinicaldata.research;

import java.util.List;

public interface ResearchAggregateRepository {
  List<ResearchAggregateRow> find(ResearchViewQuery query);
}
