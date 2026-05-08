package org.arguslog.api.config;

import java.time.Clock;
import org.arguslog.api.auth.adapter.in.web.PatAuthenticationFilter;
import org.arguslog.api.auth.application.PatUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  @Value("${arguslog.cors.allowed-origins:http://localhost:5173}")
  private String allowedOrigins;

  @Bean
  @Profile("!test")
  public SecurityFilterChain securityFilterChain(HttpSecurity http, PatUseCase pats, Clock clock)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authz ->
                authz
                    .requestMatchers(
                        "/actuator/health/**",
                        "/api/v1/info",
                        "/api/v1/platforms",
                        "/v3/api-docs/**",
                        "/swagger-ui/**")
                    .permitAll()
                    .requestMatchers("/api/v1/webhooks/stripe")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // PAT-aware bearer resolver: when the Authorization header carries an arglog_pat_*
        // token, the JWT filter sees nothing and skips. Without this guard, the JWT decoder
        // would try to parse the PAT as a JWT, fail with "Malformed token", and short-circuit
        // the request to 401 even after PatAuthenticationFilter has already authenticated it.
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .bearerTokenResolver(
                        request -> {
                          String header = request.getHeader("Authorization");
                          if (header != null && header.startsWith("Bearer arglog_pat_")) {
                            return null;
                          }
                          return new DefaultBearerTokenResolver().resolve(request);
                        })
                    .jwt(jwt -> {}))
        // PAT filter runs before the JWT filter — bearer tokens that start with `arglog_pat_`
        // resolve via the PAT path; everything else falls through to the JWT validator.
        .addFilterBefore(
            new PatAuthenticationFilter(pats, clock), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Profile("test")
  public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authz -> authz.anyRequest().permitAll());
    return http.build();
  }

  private CorsConfigurationSource corsSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    for (String origin : allowedOrigins.split(",")) {
      cfg.addAllowedOrigin(origin.trim());
    }
    cfg.addAllowedHeader("*");
    cfg.addAllowedMethod("*");
    cfg.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cfg);
    return source;
  }
}
