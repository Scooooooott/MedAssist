package com.medassist.ingestion.pipeline.grpc;

import com.medassist.contracts.v1.DocumentIR;
import com.medassist.contracts.v1.Section;
import com.medassist.contracts.v1.SourceRange;
import com.medassist.contracts.v1.TableBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps the complete parser protobuf tree into transport-neutral domain records. */
final class ParserDocumentMapper {
  private ParserDocumentMapper() {}

  static com.medassist.domain.DocumentIR toDomain(final DocumentIR document) {
    return new com.medassist.domain.DocumentIR(
        document.getSectionsList().stream().map(ParserDocumentMapper::toDomain).toList(),
        document.getTablesList().stream().map(ParserDocumentMapper::toDomain).toList(),
        document.getMetadataMap());
  }

  private static com.medassist.domain.Section toDomain(final Section section) {
    return new com.medassist.domain.Section(
        section.getPath(),
        section.getHeading(),
        section.getLevel(),
        section.getText(),
        section.getChildrenList().stream().map(ParserDocumentMapper::toDomain).toList(),
        toDomain(section.hasSourceRange() ? section.getSourceRange() : null, section.getText()));
  }

  private static com.medassist.domain.TableBlock toDomain(final TableBlock table) {
    final List<Map<String, String>> rows = new ArrayList<>();
    table.getRowsList().forEach(row -> rows.add(row.getCellsMap()));
    return new com.medassist.domain.TableBlock(
        table.getSectionPath(),
        table.getCaption(),
        table.getHeadersList(),
        rows,
        table.getLinearizedText(),
        toDomain(
            table.hasSourceRange() ? table.getSourceRange() : null, table.getLinearizedText()));
  }

  private static com.medassist.domain.SourceRange toDomain(
      final SourceRange sourceRange, final String text) {
    return sourceRange == null
        ? new com.medassist.domain.SourceRange(0, text.length())
        : new com.medassist.domain.SourceRange(sourceRange.getStart(), sourceRange.getEnd());
  }
}
