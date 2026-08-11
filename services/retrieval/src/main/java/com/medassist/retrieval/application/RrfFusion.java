package com.medassist.retrieval.application;

import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.repository.RankedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class RrfFusion {
  public List<RetrievedChunk> fuse(
      final List<RankedChunk> vector,
      final List<RankedChunk> lexical,
      final int topK,
      final int rankConstant,
      final double vectorWeight,
      final double lexicalWeight) {
    if (topK < 1 || rankConstant < 1 || vectorWeight < 0 || lexicalWeight < 0) {
      throw new IllegalArgumentException("invalid RRF configuration");
    }
    final Map<UUID, Accumulator> accumulators = new LinkedHashMap<>();
    vector.forEach(item -> add(accumulators, item, true, rankConstant, vectorWeight));
    lexical.forEach(item -> add(accumulators, item, false, rankConstant, lexicalWeight));
    return accumulators.values().stream()
        .sorted(
            Comparator.comparingDouble(Accumulator::fusedScore)
                .reversed()
                .thenComparing(item -> item.chunk().chunkId()))
        .limit(topK)
        .map(Accumulator::toChunk)
        .toList();
  }

  private void add(
      final Map<UUID, Accumulator> accumulators,
      final RankedChunk ranked,
      final boolean vector,
      final int rankConstant,
      final double weight) {
    final Accumulator accumulator =
        accumulators.computeIfAbsent(
            ranked.chunk().chunkId(), ignored -> new Accumulator(ranked.chunk()));
    accumulator.add(ranked, vector, weight / (rankConstant + ranked.rank()));
  }

  private static final class Accumulator {
    private final RetrievedChunk chunk;
    private int vectorRank;
    private int lexicalRank;
    private Double vectorScore;
    private Double lexicalScore;
    private double fusedScore;
    private final List<String> methods = new ArrayList<>();

    private Accumulator(final RetrievedChunk chunk) {
      this.chunk = chunk;
    }

    private void add(final RankedChunk ranked, final boolean vector, final double contribution) {
      fusedScore += contribution;
      if (vector) {
        vectorRank = ranked.rank();
        vectorScore = ranked.channelScore();
        methods.add("VECTOR");
      } else {
        lexicalRank = ranked.rank();
        lexicalScore = ranked.channelScore();
        methods.add("LEXICAL");
      }
    }

    private RetrievedChunk chunk() {
      return chunk;
    }

    private double fusedScore() {
      return fusedScore;
    }

    private RetrievedChunk toChunk() {
      return new RetrievedChunk(
          chunk.chunkId(),
          chunk.documentVersionId(),
          chunk.ordinal(),
          chunk.sectionPath(),
          chunk.text(),
          chunk.tokenCount(),
          chunk.sourceCharStart(),
          chunk.sourceCharEnd(),
          fusedScore,
          String.join("+", methods) + "_RRF",
          chunk.distanceMetric(),
          chunk.docType(),
          chunk.publisher(),
          chunk.sourceTitle(),
          chunk.version(),
          chunk.effectiveDate(),
          chunk.documentStatus(),
          chunk.stale(),
          vectorRank == 0 ? null : vectorRank,
          lexicalRank == 0 ? null : lexicalRank,
          vectorScore,
          lexicalScore,
          fusedScore,
          chunk.metadata());
    }
  }
}
