package com.medassist.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Emits the same safe RFC 9457 shape for service-side authentication failures. */
public final class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final ObjectMapper objectMapper;

  public ProblemSecurityHandler(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception)
      throws IOException, ServletException {
    write(response, 401, "authentication-required", "Authentication is required.", request);
  }

  @Override
  public void handle(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AccessDeniedException exception)
      throws IOException, ServletException {
    write(response, 403, "access-denied", "Access is denied.", request);
  }

  private void write(
      final HttpServletResponse response,
      final int status,
      final String code,
      final String detail,
      final HttpServletRequest request)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    final Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", "urn:medassist:problem:" + code);
    problem.put("title", status == 401 ? "Unauthorized" : "Forbidden");
    problem.put("status", status);
    problem.put("detail", detail);
    problem.put("instance", request.getRequestURI());
    problem.put("code", code);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
