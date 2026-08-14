package com.medassist.common.context;

import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.util.Locale;
import java.util.Objects;

/**
 * Converts the already-authenticated execution context into request-bound values.
 *
 * <p>HTTP adapters must call this boundary instead of accepting actor or role values from a request
 * body. The component that authenticates a request is intentionally outside this class; M5.1 will
 * bind its verified JWT claims to {@link ContextCarrier}.
 */
public final class AuthenticatedRequestContext {
  private AuthenticatedRequestContext() {}

  public static ExecutionContext requireCurrent() {
    return ContextCarrier.requireCurrent();
  }

  public static Role requireSingleRole() {
    return requireSingleRole(requireCurrent());
  }

  public static Role requireSingleRole(final ExecutionContext context) {
    Objects.requireNonNull(context, "context");
    if (context.roles().size() != 1) {
      throw new InvalidExecutionContextException(
          "exactly one effective role is required at the HTTP boundary");
    }
    final String value = context.roles().iterator().next();
    try {
      return Role.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException exception) {
      throw new InvalidExecutionContextException("execution context contains an unknown role");
    }
  }

  public static RequestIds requireRequestIds(final ExecutionContext context) {
    Objects.requireNonNull(context, "context");
    return new RequestIds(context.traceId(), context.requestId());
  }
}
