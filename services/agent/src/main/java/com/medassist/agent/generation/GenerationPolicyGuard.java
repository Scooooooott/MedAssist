package com.medassist.agent.generation;

import com.medassist.common.context.AuthenticatedRequestContext;
import com.medassist.common.context.ExecutionContext;
import java.util.Arrays;
import java.util.Set;

/**
 * Revalidates ownership, exact role set, active policy version, and optional action obligations.
 */
final class GenerationPolicyGuard {
  static final String POLICY_VERSION_OBLIGATION = "policy_version";
  static final String ACTIONS_OBLIGATION = "generation_actions";

  private final String activePolicyVersion;

  GenerationPolicyGuard(final String activePolicyVersion) {
    this.activePolicyVersion = activePolicyVersion;
  }

  String authorizeCreate(final ExecutionContext context) {
    AuthenticatedRequestContext.requireSingleRole(context);
    requireCurrentPolicy(context);
    requireAction(context, "create");
    return activePolicyVersion;
  }

  void authorize(
      final GenerationSession session, final ExecutionContext context, final String action) {
    if (!session.ownerSubject().equals(context.subject())) {
      throw forbidden();
    }
    if (!session.roles().equals(context.roles())) {
      throw forbidden();
    }
    requireCurrentPolicy(context);
    if (!session.policyVersion().equals(activePolicyVersion)) {
      throw forbidden();
    }
    requireAction(context, action);
  }

  private void requireCurrentPolicy(final ExecutionContext context) {
    final String contextVersion = context.obligations().get(POLICY_VERSION_OBLIGATION);
    if (contextVersion != null && !activePolicyVersion.equals(contextVersion)) {
      throw forbidden();
    }
  }

  private static void requireAction(final ExecutionContext context, final String action) {
    final String configured = context.obligations().get(ACTIONS_OBLIGATION);
    if (configured == null) {
      return;
    }
    final Set<String> actions = Set.copyOf(Arrays.asList(configured.split(",")));
    if (!actions.contains(action) && !actions.contains("*")) {
      throw forbidden();
    }
  }

  private static GenerationException forbidden() {
    return new GenerationException(
        GenerationException.Reason.FORBIDDEN, "generation session access is forbidden");
  }
}
