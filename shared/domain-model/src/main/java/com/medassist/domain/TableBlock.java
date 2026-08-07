package com.medassist.domain;

import java.util.List;
import java.util.Map;

public record TableBlock(
    String sectionPath,
    String caption,
    List<String> headers,
    List<Map<String, String>> rows,
    String linearizedText,
    SourceRange sourceRange) {
  public TableBlock {
    sectionPath = sectionPath == null ? "" : sectionPath;
    caption = caption == null ? "" : caption;
    headers = List.copyOf(headers);
    rows = List.copyOf(rows);
    linearizedText = linearizedText == null ? "" : linearizedText;
    sourceRange = sourceRange == null ? new SourceRange(0, linearizedText.length()) : sourceRange;
  }

  public TableBlock(final String caption, final List<String> headers, final List<Map<String, String>> rows) {
    this("", caption, headers, rows, "", null);
  }
}
