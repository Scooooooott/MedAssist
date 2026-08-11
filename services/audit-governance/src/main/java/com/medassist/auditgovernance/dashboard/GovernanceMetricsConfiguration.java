package com.medassist.auditgovernance.dashboard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GovernanceMetricsConfiguration {
  @Bean
  GovernanceMetricsService governanceMetricsService() {
    return new EmptyGovernanceMetricsService();
  }
}
