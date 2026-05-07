package org.arguslog.api.auth.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.auth.application.PatUseCase;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sniffs {@code Authorization: Bearer arglog_pat_…} on the inbound request, verifies the PAT, and
 * sets a {@link PatAuthentication} in the security context. Anything else falls through to the
 * existing JWT resource-server filter unchanged.
 *
 * <p>Auth here is intentionally cheap-fail: invalid PATs do NOT short-circuit the filter chain (we
 * just don't authenticate). The downstream {@code authorizeHttpRequests().anyRequest()
 * .authenticated()} stage will return 401 if no other filter authenticates either.
 */
public class PatAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String PAT_TOKEN_PREFIX = "arglog_pat_";

  private final PatUseCase pats;
  private final Clock clock;

  public PatAuthenticationFilter(PatUseCase pats, Clock clock) {
    this.pats = pats;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null
        && header.startsWith(BEARER_PREFIX)
        && header.startsWith(BEARER_PREFIX + PAT_TOKEN_PREFIX)) {
      String wire = header.substring(BEARER_PREFIX.length());
      Optional<PersonalAccessToken> verified = pats.verify(wire, java.time.Instant.now(clock));
      verified.ifPresent(
          token ->
              SecurityContextHolder.getContext().setAuthentication(new PatAuthentication(token)));
    }
    chain.doFilter(request, response);
  }

  /**
   * Authenticated principal backed by a PAT. {@link #getName()} returns the user's UUID as a String
   * so the rest of the stack ({@code ProjectAccessGuard}, etc.) treats PAT auth and JWT auth
   * identically.
   */
  public static final class PatAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    // PersonalAccessToken is not Serializable. Marking transient because Spring's session
    // serialization is irrelevant in our stateless setup, but the lint check still wants it.
    private final transient PersonalAccessToken token;

    public PatAuthentication(PersonalAccessToken token) {
      super(authoritiesFor(token));
      this.token = token;
      setAuthenticated(true);
    }

    // ROLE_USER keeps PAT auth interchangeable with JWT auth for everything that just wants a
    // logged-in user. SCOPE_* authorities back
    // @PreAuthorize("hasAuthority('SCOPE_releases:write')")
    // gates so a token without the scope 403s instead of silently going through.
    private static Collection<GrantedAuthority> authoritiesFor(PersonalAccessToken token) {
      List<GrantedAuthority> out = new ArrayList<>();
      out.add(new SimpleGrantedAuthority("ROLE_USER"));
      for (PatScope scope : token.effectiveScopes()) {
        out.add(new SimpleGrantedAuthority(scope.authority()));
      }
      return out;
    }

    @Override
    public Object getCredentials() {
      return ""; // never echo the token
    }

    @Override
    public Object getPrincipal() {
      return token.userId();
    }

    @Override
    public String getName() {
      return token.userId().toString();
    }

    public PersonalAccessToken token() {
      return token;
    }
  }
}
