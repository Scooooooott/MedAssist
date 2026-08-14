package com.medassist.common.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medassist.domain.Role;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuthenticatedRequestContextTest {
  @AfterEach
  void clearContext() {
    ContextCarrier.clear();
  }

  @Test
  void requiresAnAuthenticatedContext() {
    assertThrows(
        MissingExecutionContextException.class, AuthenticatedRequestContext::requireCurrent);
  }

  @Test
  void resolvesOneKnownRoleAndRequestIds() {
    final ExecutionContext context = context(Set.of("researcher"));
    ContextCarrier.restore(context);

    assertEquals(Role.RESEARCHER, AuthenticatedRequestContext.requireSingleRole());
    assertEquals(
        new com.medassist.common.RequestIds("trace-1", "request-1"),
        AuthenticatedRequestContext.requireRequestIds(context));
  }

  @Test
  void rejectsMultipleOrUnknownRoles() {
    assertThrows(
        InvalidExecutionContextException.class,
        () -> AuthenticatedRequestContext.requireSingleRole(context(Set.of("CLINICIAN", "ADMIN"))));
    assertThrows(
        InvalidExecutionContextException.class,
        () -> AuthenticatedRequestContext.requireSingleRole(context(Set.of("service"))));
  }

  private ExecutionContext context(final Set<String> roles) {
    return new ExecutionContext("subject-1", roles, "request-1", "trace-1", Map.of());
  }
}
