package com.medassist.clinicaldata.config;

import ca.uhn.fhir.context.FhirContext;
import com.medassist.clinicaldata.deid.SafeHarborMapper;
import com.medassist.clinicaldata.fhir.FhirProfileValidator;
import com.medassist.clinicaldata.persistence.ClinicalBundleImportService;
import com.medassist.clinicaldata.persistence.ClinicalImportPersistencePort;
import com.medassist.clinicaldata.persistence.JdbcClinicalImportPersistenceAdapter;
import com.medassist.clinicaldata.query.JdbcStructuredQueryExecutor;
import com.medassist.clinicaldata.query.StructuredQueryBoundary;
import com.medassist.clinicaldata.query.StructuredQueryExecutor;
import com.medassist.clinicaldata.query.StructuredQueryService;
import com.medassist.clinicaldata.research.JdbcResearchAggregateRepository;
import com.medassist.clinicaldata.research.ResearchAggregateRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ClinicalDataConfiguration {
  @Bean
  FhirContext fhirContext() {
    return FhirContext.forR4();
  }

  @Bean
  FhirProfileValidator fhirProfileValidator(final ClinicalImportProperties properties) {
    return new FhirProfileValidator(properties);
  }

  @Bean
  SafeHarborMapper safeHarborMapper() {
    return new SafeHarborMapper(Clock.systemUTC());
  }

  @Bean
  @ConditionalOnBean({JdbcOperations.class, PlatformTransactionManager.class})
  @ConditionalOnMissingBean(ClinicalImportPersistencePort.class)
  ClinicalImportPersistencePort clinicalImportPersistencePort(
      final JdbcOperations jdbc, final PlatformTransactionManager transactionManager) {
    return new JdbcClinicalImportPersistenceAdapter(jdbc, transactionManager);
  }

  @Bean
  @ConditionalOnBean(ClinicalImportPersistencePort.class)
  ClinicalBundleImportService clinicalBundleImportService(
      final com.medassist.clinicaldata.fhir.HapiFhirBundleImporter importer,
      final ClinicalImportPersistencePort persistence) {
    return new ClinicalBundleImportService(importer, persistence);
  }

  @Bean
  @ConditionalOnBean(JdbcOperations.class)
  StructuredQueryExecutor structuredQueryExecutor(
      final JdbcOperations jdbc,
      final StructuredQueryBoundary boundary,
      final ClinicalQueryProperties properties) {
    return new JdbcStructuredQueryExecutor(jdbc, boundary, properties);
  }

  @Bean
  @ConditionalOnBean(StructuredQueryExecutor.class)
  StructuredQueryService structuredQueryService(
      final StructuredQueryBoundary boundary, final StructuredQueryExecutor executor) {
    return new StructuredQueryService(boundary, executor);
  }

  @Bean
  @ConditionalOnBean(NamedParameterJdbcTemplate.class)
  @ConditionalOnMissingBean(ResearchAggregateRepository.class)
  ResearchAggregateRepository researchAggregateRepository(
      final NamedParameterJdbcTemplate jdbc, final ClinicalQueryProperties properties) {
    return new JdbcResearchAggregateRepository(jdbc, properties);
  }
}
