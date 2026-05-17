package org.arguslog.api.slack.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Buffers the request body for Slack slash-command POSTs so the controller can recompute the HMAC
 * against the exact bytes Slack signed.
 *
 * <p>Slack POSTs slash-command payloads as {@code application/x-www-form-urlencoded}. With that
 * content-type Tomcat lazily parses the body into the parameter map the first time any caller
 * touches {@code getParameter*} — and once the input stream is drained, the controller's {@code
 * request.getReader()} returns empty. That made every signed payload mismatch and 401 in production
 * even though the unit tests (which mock the verifier) were green.
 *
 * <p>Running this filter at {@link Ordered#HIGHEST_PRECEDENCE} guarantees we read the raw bytes
 * before any other filter has a chance to trigger parameter parsing. The wrapper then serves the
 * buffered bytes for every subsequent {@code getInputStream}/{@code getReader} call, so both the
 * HMAC verification AND the form-parsing in the controller see identical input.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlackBodyCachingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("POST".equals(request.getMethod())
        && (request.getRequestURI().startsWith("/api/v1/slack/commands")
            || request.getRequestURI().startsWith("/api/v1/slack/interactivity"))) {
      chain.doFilter(new CachedBodyHttpServletRequest(request), response);
      return;
    }
    chain.doFilter(request, response);
  }

  static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
      super(request);
      this.body = request.getInputStream().readAllBytes();
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream backing = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return backing.read();
        }

        @Override
        public boolean isFinished() {
          return backing.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {}
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
