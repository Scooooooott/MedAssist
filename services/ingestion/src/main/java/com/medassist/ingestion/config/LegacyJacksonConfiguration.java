package com.medassist.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jackson 2 mapper for persisted stage and audit boundaries while Boot 4 defaults to Jackson 3. */
@Configuration
public class LegacyJacksonConfiguration {
  @Bean
  ObjectMapper legacyObjectMapper() {
    return JsonMapper.builder().findAndAddModules().build();
  }
}
