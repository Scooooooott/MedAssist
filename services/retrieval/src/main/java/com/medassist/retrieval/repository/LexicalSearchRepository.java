package com.medassist.retrieval.repository;

import com.medassist.retrieval.application.model.SearchQuery;
import java.util.List;

public interface LexicalSearchRepository {
  List<RankedChunk> search(SearchQuery query);
}
