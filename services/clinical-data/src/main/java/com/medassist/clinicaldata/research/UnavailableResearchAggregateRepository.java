package com.medassist.clinicaldata.research;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicitly unavailable until a production persistence adapter is configured. */
@Repository
@ConditionalOnMissingBean(NamedParameterJdbcTemplate.class)
public class UnavailableResearchAggregateRepository implements ResearchAggregateRepository {
  @Override
  public List<ResearchAggregateRow> find(final ResearchViewQuery query) {
    throw new IllegalStateException("clinical research aggregate repository is not configured");
  }
}
