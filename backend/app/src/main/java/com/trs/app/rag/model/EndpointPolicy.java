package com.trs.app.rag.model;

import java.net.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment-controlled model destinations; credentials cannot be sent to arbitrary hosts. @author
 * agent
 */
@Component
public class EndpointPolicy {
  private final Set<String> hosts;
  private final boolean http;

  /**
   * Captures an explicit host allowlist; empty means no configured model destinations. @author
   * agent
   */
  public EndpointPolicy(
      @Value("${rag.models.allowed-hosts:}") String allowed,
      @Value("${rag.models.allow-http:false}") boolean http) {
    this.hosts = new HashSet<>();
    for (String s : allowed.split(","))
      if (!s.isBlank()) hosts.add(s.strip().toLowerCase(Locale.ROOT));
    this.http = http;
  }

  /**
   * Validates an exact hostname, scheme and safe URL before storage and every request. @author
   * agent
   */
  public URI validate(String address) {
    try {
      URI u = URI.create(address);
      if (u.getHost() == null
          || !hosts.contains(u.getHost().toLowerCase(Locale.ROOT))
          || u.getRawUserInfo() != null
          || u.getRawQuery() != null
          || u.getRawFragment() != null
          || !("https".equals(u.getScheme()) || (http && "http".equals(u.getScheme())))
          || u.getPath().contains("..")
          || address.length() > 1000) throw new IllegalArgumentException();
      return u;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Model endpoint is not allowed");
    }
  }
}
