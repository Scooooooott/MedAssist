package com.medassist.clinicaldata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class ClinicalDataApplication {
  public static void main(final String[] args) {
    SpringApplication.run(ClinicalDataApplication.class, args);
  }
}
