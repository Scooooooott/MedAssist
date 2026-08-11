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
          "你好",
          "谢谢",
          "闲聊",
          "天气",
          "股票",
          "体育",
          "旅游");

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
          "政策",
          "指南",
          "规范",
          "规程",
          "合规",
          "药品说明书");

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
          "病例",
          "症状",
          "患者",
          "诊断",
          "治疗",
          "药物",
          "药",
          "剂量",
          "副作用",
          "疾病");

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
          "统计",
          "聚合",
          "队列",
          "分组",
          "数量",
          "总数",
          "分布",
          "比例",
          "均值",
          "平均");

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
    return query.contains("diagnos") || query.contains("诊疗");
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
            "统计",
            "聚合",
            "队列",
            "分组",
            "数量",
            "总数",
            "分布",
            "比例",
            "均值",
            "平均"));
  }
}
