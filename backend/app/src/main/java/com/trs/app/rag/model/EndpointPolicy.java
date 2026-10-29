package com.trs.app.rag.model;

import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * Validates administrator-saved model addresses without deployment-specific host settings.
 *
 * @author czhcc
 */
@Component
public class EndpointPolicy {
  /**
   * Accepts configured HTTP/HTTPS destinations while rejecting embedded credentials and invalid URLs.
   *
   * @param address base URL from the captured model configuration
   * @return validated model base URI
   * @author czhcc
   */
  public URI validate(String address) {
    try {
      URI u = URI.create(address);
      if (u.getHost() == null
          || u.getRawUserInfo() != null
          || u.getRawQuery() != null
          || u.getRawFragment() != null
          || !("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
          || (u.getPort() != -1 && (u.getPort() < 1 || u.getPort() > 65535))
          || u.getPath().contains("..")
          || address.length() > 1000) throw new IllegalArgumentException();
      return u;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid model endpoint");
    }
  }
}
