package com.medassist.agent.routing;

import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.QueryClassification;
import java.util.Objects;

@FunctionalInterface
public interface QueryClassifier {
  QueryClassification classify(String query);

  default QueryClassification classify(final AgentState state) {
    Objects.requireNonNull(state, "state");
    return classify(state.deidentifiedQuery());
  }
}
