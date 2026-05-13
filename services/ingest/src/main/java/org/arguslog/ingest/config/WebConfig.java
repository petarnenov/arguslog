package org.arguslog.ingest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the public ingest endpoint.
 *
 * <p>Browser SDKs run on customers' own apps — any origin can have a legitimate reason to POST an
 * event with a valid DSN. The DSN's public key (delivered in the {@code X-Arguslog-Auth} header) is
 * what authenticates a request, not the {@code Origin}, so we allow any origin and skip credentials
 * (cookies/Authorization aren't part of this contract).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(@NonNull CorsRegistry registry) {
    registry
        .addMapping("/api/*/events")
        .allowedOriginPatterns("*")
        .allowedMethods("POST", "OPTIONS")
        .allowedHeaders("Content-Type", "X-Arguslog-Auth")
        .allowCredentials(false)
        .maxAge(3600);

    // Public status page on arguslog.org polls these health endpoints from the browser to render
    // a real-time uptime indicator. The data is identical to what curl already returns from
    // anywhere — no auth, no credentials — so allowing any origin matches the existing public
    // exposure of /actuator/health.
    registry
        .addMapping("/actuator/health/**")
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "OPTIONS")
        .allowCredentials(false)
        .maxAge(3600);
  }
}
