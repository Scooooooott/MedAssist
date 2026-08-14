package com.medassist.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medassist.security")
public class MedAssistSecurityProperties {
  private boolean enabled = true;
  private String jwkSetUri = "";
  private String issuer = "http://localhost:8081/realms/medassist";
  private String audience = "medassist";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public String getJwkSetUri() {
    return jwkSetUri;
  }

  public void setJwkSetUri(final String jwkSetUri) {
    this.jwkSetUri = jwkSetUri == null ? "" : jwkSetUri;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(final String issuer) {
    this.issuer = issuer == null ? "" : issuer;
  }

  public String getAudience() {
    return audience;
  }

  public void setAudience(final String audience) {
    this.audience = audience == null ? "" : audience;
  }
}
