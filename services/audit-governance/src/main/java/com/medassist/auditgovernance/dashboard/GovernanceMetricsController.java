package com.medassist.auditgovernance.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BFF boundary; it never exposes a repository or database connection to the frontend. */
@RestController
@RequestMapping("/api/governance/metrics")
public final class GovernanceMetricsController {
  private final GovernanceMetricsService metricsService;

  public GovernanceMetricsController(final GovernanceMetricsService metricsService) {
    this.metricsService = metricsService;
  }

  @GetMapping
  public ResponseEntity<?> get(
      @RequestParam(name = "dashboard", defaultValue = "QUALITY") final String dashboardValue,
      @RequestParam(name = "from", required = false) final Instant from,
      @RequestParam(name = "to", required = false) final Instant to,
      final HttpServletRequest request) {
    final DashboardKind dashboard = DashboardKind.parse(dashboardValue);
    if (dashboard == null) {
      return ResponseEntity.badRequest().body("unknown dashboard");
    }
    if (request.getUserPrincipal() == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    if (!canRead(request, dashboard)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    final Instant end = to == null ? Instant.now() : to;
    final Instant start = from == null ? end.minus(30, ChronoUnit.DAYS) : from;
    return ResponseEntity.ok(metricsService.snapshot(dashboard, start, end));
  }

  private static boolean canRead(final HttpServletRequest request, final DashboardKind dashboard) {
    return switch (dashboard) {
      case GOVERNANCE -> request.isUserInRole("ADMIN");
      case QUALITY, COST -> request.isUserInRole("ADMIN") || request.isUserInRole("RESEARCHER");
    };
  }
}
