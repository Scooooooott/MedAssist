package com.medassist.common.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Provides the legacy Jackson 2 mapper still used by selected service adapters on Boot 4. */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class Jackson2CompatibilityAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper jackson2ObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
