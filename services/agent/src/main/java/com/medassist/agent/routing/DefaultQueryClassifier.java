package com.medassist.agent.routing;

import com.medassist.agent.state.QueryClassification;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic, deliberately conservative query classification. */
public final class DefaultQueryClassifier implements QueryClassifier {
  private static final Set<String> OUT_OF_SCOPE_SIGNALS =
      Set.of(
          "weather",
          "weather forecast",
          "stock price",
          "stock market",
          "stocks",
          "share price",
          "bitcoin",
          "cryptocurrency",
          "sports score",
          "sports",
          "recipe",
          "restaurant",
          "travel",
          "movie",
          "music",
          "joke",
          "poem",
          "hello",
          "hi",
          "hey",
          "thanks",
          "thank you",
          "how are you",
          "what's up",
          "\u6d63\u72b2\u30bd",
          "\u748b\u3223\u963f",
          "\u95c2\u8336\u4eb0",
          "\u6fb6\u2542\u76b5",
          "\u9472\uff04\u30a8",
          "\u6d63\u64b9\u505b",
          "\u93c3\u546e\u7236");

  private static final Set<String> POLICY_SIGNALS =
      Set.of(
          "policy",
          "policies",
          "guideline",
          "guidelines",
          "protocol",
          "standard of care",
          "regulation",
          "regulatory",
          "compliance",
          "consent policy",
          "hospital policy",
          "drug label",
          "formulary",
          "sop",
          "\u93c0\u8de8\u74e5",
          "\u93b8\u56e7\u5d21",
          "\u7459\u52ee\u5bd6",
          "\u7459\u52ed\u25bc",
          "\u935a\u5823\ue749",
          "\u947d\ue21a\u6427\u7487\u5b58\u69d1\u6d94");

  private static final Set<String> CLINICAL_SIGNALS =
      Set.of(
          "symptom",
          "symptoms",
          "patient",
          "patients",
          "case report",
          "clinical",
          "diagnosis",
          "diagnostic",
          "treatment",
          "therapy",
          "medication",
          "medicine",
          "drug",
          "dose",
          "dosage",
          "side effect",
          "adverse effect",
          "contraindication",
          "prescribe",
          "disease",
          "condition",
          "pain",
          "fever",
          "cough",
          "blood pressure",
          "lab result",
          "\u9425\u546c\u7de5",
          "\u9425\u56e9\u59f8",
          "\u93ae\uff48\u20ac",
          "\u7487\u5a43\u67c7",
          "\u5a0c\u8364\u679f",
          "\u947d\ue21c\u58bf",
          "\u947d",
          "\u9353\u509e\u567a",
          "\u9353\ue219\u7d94\u9422",
          "\u9424\u5267\u68be");

  private static final Set<String> STRUCTURED_SIGNALS =
      Set.of(
          "sql",
          "database",
          "table",
          "column",
          "row",
          "count",
          "sum",
          "average",
          "avg",
          "mean",
          "median",
          "percentile",
          "group by",
          "aggregate",
          "aggregation",
          "distribution",
          "cohort",
          "queue",
          "backlog",
          "how many",
          "number of",
          "\u7f01\u71bb\ue178",
          "\u9471\u6c2c\u608e",
          "\u95c3\u71b7\u57aa",
          "\u9352\u55d9\u7c8d",
          "\u93c1\u4f34\u567a",
          "\u93ac\u7ed8\u669f",
          "\u9352\u55d7\u7af7",
          "\u59e3\u65be\u7de5",
          "\u9367\u56e7\u20ac",
          "\u9a9e\u51b2\u6f4e");

  @Override
  public QueryClassification classify(final String query) {
    final String normalized = normalize(query);
    if (normalized.isEmpty()) {
      return QueryClassification.MIXED;
    }

    final boolean outOfScope = containsAny(normalized, OUT_OF_SCOPE_SIGNALS);
    final boolean policy = containsAny(normalized, POLICY_SIGNALS);
    final boolean clinical =
        containsAny(normalized, CLINICAL_SIGNALS) || containsDiagnosisStem(normalized);
    final boolean structured = containsAny(normalized, STRUCTURED_SIGNALS);
    final int inScopeKinds = (policy ? 1 : 0) + (clinical ? 1 : 0) + (structured ? 1 : 0);

    if (outOfScope && inScopeKinds == 0) {
      return QueryClassification.OUT_OF_SCOPE;
    }
    if (structured && !policy && isStronglyStructured(normalized)) {
      return QueryClassification.STRUCTURED;
    }
    if (inScopeKinds != 1 || outOfScope) {
      return QueryClassification.MIXED;
    }
    if (policy) {
      return QueryClassification.POLICY;
    }
    if (clinical) {
      return QueryClassification.CLINICAL;
    }
    if (structured) {
      return QueryClassification.STRUCTURED;
    }
    return QueryClassification.MIXED;
  }

  private static String normalize(final String query) {
    return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean containsAny(final String query, final Set<String> signals) {
    return signals.stream().anyMatch(signal -> containsSignal(query, signal));
  }

  private static boolean containsSignal(final String query, final String signal) {
    if (signal
        .codePoints()
        .allMatch(codePoint -> codePoint < 128 && Character.isLetterOrDigit(codePoint))) {
      return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(signal) + "(?![\\p{L}\\p{N}])")
          .matcher(query)
          .find();
    }
    return query.contains(signal);
  }

  private static boolean containsDiagnosisStem(final String query) {
    return query.contains("diagnos") || query.contains("\u7487\u5a44\u679f");
  }

  private static boolean isStronglyStructured(final String query) {
    return containsAny(
        query,
        Set.of(
            "sql",
            "database",
            "table",
            "column",
            "row",
            "count",
            "sum",
            "average",
            "avg",
            "mean",
            "median",
            "percentile",
            "group by",
            "aggregate",
            "aggregation",
            "distribution",
            "cohort",
            "queue",
            "backlog",
            "how many",
            "number of",
            "\u7f01\u71bb\ue178",
            "\u9471\u6c2c\u608e",
            "\u95c3\u71b7\u57aa",
            "\u9352\u55d9\u7c8d",
            "\u93c1\u4f34\u567a",
            "\u93ac\u7ed8\u669f",
            "\u9352\u55d7\u7af7",
            "\u59e3\u65be\u7de5",
            "\u9367\u56e7\u20ac",
            "\u9a9e\u51b2\u6f4e"));
  }
}
