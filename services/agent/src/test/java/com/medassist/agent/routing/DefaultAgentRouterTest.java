package com.medassist.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.execution.DefaultAgentRouter;
import com.medassist.agent.execution.RouteDecision;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.QueryClassification;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultAgentRouterTest {
  @Test
  void classifiesEachSupportedQueryKindDeterministically() {
    final DefaultQueryClassifier classifier = new DefaultQueryClassifier();

    assertEquals(
        QueryClassification.POLICY,
        classifier.classify("What is the hospital policy for consent?"));
    assertEquals(
        QueryClassification.CLINICAL,
        classifier.classify("What treatment and dose fit these symptoms?"));
    assertEquals(
        QueryClassification.STRUCTURED,
        classifier.classify("Run an SQL count grouped by category."));
    assertEquals(
        QueryClassification.MIXED,
        classifier.classify("Which policy applies to this patient's treatment?"));
    assertEquals(
        QueryClassification.OUT_OF_SCOPE, classifier.classify("What is the weather forecast?"));
    assertEquals(QueryClassification.OUT_OF_SCOPE, classifier.classify("Hello, how are you?"));
    assertEquals(QueryClassification.MIXED, classifier.classify("Can you help with this?"));
  }

  @Test
  void outOfScopeNeverCarriesToolsAndAlwaysAbstains() {
    final RouteDecision decision = route("Tell me the stock price", Role.CLINICIAN);

    assertEquals(QueryClassification.OUT_OF_SCOPE, decision.classification());
    assertEquals(Set.of(), decision.allowedTools());
    assertEquals(AgentNode.ABSTAIN, decision.nextNode());
  }

  @Test
  void roleAllowlistIsAppliedBeforeTheDecisionIsReturned() {
    assertEquals(
        Set.of("policy_search"), route("What does the policy say?", Role.CLINICIAN).allowedTools());
    assertEquals(
        Set.of("clinical_search"),
        route("What are the patient's symptoms?", Role.CLINICIAN).allowedTools());
    assertEquals(
        Set.of("structured_query"),
        route("How many records are in each category?", Role.CLINICIAN).allowedTools());

    assertEquals(
        Set.of("policy_search"),
        route("What does the policy say?", Role.RESEARCHER).allowedTools());
    assertEquals(
        Set.of(), route("What are the patient's symptoms?", Role.RESEARCHER).allowedTools());
    assertEquals(
        Set.of("structured_query"),
        route("How many records are in each category?", Role.RESEARCHER).allowedTools());

    assertEquals(
        Set.of("policy_search"), route("What does the policy say?", Role.ADMIN).allowedTools());
    assertEquals(Set.of(), route("What are the patient's symptoms?", Role.ADMIN).allowedTools());
  }

  @Test
  void researcherStructuredToolRejectsNonAggregateQueriesAtRoutingTime() {
    final RouteDecision decision = route("Show the rows in the queue", Role.RESEARCHER);

    assertEquals(QueryClassification.STRUCTURED, decision.classification());
    assertEquals(Set.of(), decision.allowedTools());
    assertEquals(AgentNode.ABSTAIN, decision.nextNode());
  }

  @Test
  void lowConfidenceQueriesUseMixedInsteadOfGuessing() {
    final RouteDecision decision = route("Please explain this", Role.CLINICIAN);

    assertEquals(QueryClassification.MIXED, decision.classification());
    assertEquals(
        Set.of("policy_search", "clinical_search", "structured_query"), decision.allowedTools());
    assertEquals(AgentNode.TOOL, decision.nextNode());
  }

  private static RouteDecision route(final String query, final Role role) {
    final AgentState state =
        AgentState.start(RequestIds.create(), new DeidentifiedQuery(query), role);
    return new DefaultAgentRouter().route(state);
  }
}
