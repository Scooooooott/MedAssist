package com.medassist.domain;

import java.util.List;
import java.util.Map;

public record DocumentIR(
    List<Section> sections, List<TableBlock> tables, Map<String, String> metadata) {
  public DocumentIR {
    sections = List.copyOf(sections);
    tables = List.copyOf(tables);
    metadata = Map.copyOf(metadata);
  }
}
