package com.medassist.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

@SpringBootTest
class GatewayApplicationTests {
  private final RouteDefinitionLocator routes;

  @Autowired
  GatewayApplicationTests(final RouteDefinitionLocator routes) {
    this.routes = routes;
  }

  @Test
  void configuresAllDownstreamAndGenerationRoutesWithoutRemovingAuthorization() {
    final var definitions = routes.getRouteDefinitions().collectList().block();
    final Set<String> routeIds =
        definitions.stream().map(RouteDefinition::getId).collect(Collectors.toSet());

    assertTrue(
        routeIds.containsAll(
            Set.of(
                "identity-policy",
                "ingestion",
                "clinical-data",
                "retrieval",
                "agent",
                "audit-governance",
                "generation-events",
                "generation-sessions")));
    assertFalse(
        definitions.stream()
            .filter(route -> route.getId().startsWith("generation-"))
            .flatMap(route -> route.getFilters().stream())
            .anyMatch(
                filter ->
                    filter.getName().equals("RemoveRequestHeader")
                        && filter.getArgs().containsValue("Authorization")));
    final RouteDefinition retrieval =
        definitions.stream()
            .filter(route -> route.getId().equals("retrieval"))
            .findFirst()
            .orElseThrow();
    assertFalse(retrieval.toString().contains("/internal/"));
    assertFalse(retrieval.toString().contains("answer"));
  }
}
