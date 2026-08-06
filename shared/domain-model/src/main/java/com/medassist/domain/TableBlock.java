package com.medassist.domain;

import java.util.List;
import java.util.Map;

public record TableBlock(String caption, List<String> headers, List<Map<String, String>> rows) {
  public TableBlock {
    headers = List.copyOf(headers);
    rows = List.copyOf(rows);
  }
}
