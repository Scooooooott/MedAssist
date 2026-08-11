package com.medassist.retrieval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jackson 2 mapper for Spring AI and cache boundaries while Boot 4 uses Jackson 3 by default. */
@Configuration
public class LegacyJacksonConfiguration {
  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper legacyObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
