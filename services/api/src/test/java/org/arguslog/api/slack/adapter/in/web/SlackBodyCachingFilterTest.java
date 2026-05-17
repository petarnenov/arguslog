package org.arguslog.api.slack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Filter must wrap incoming Slack command POSTs so the body survives Tomcat's eager form-urlencoded
 * parameter parsing — that was the production bug behind every /arguslog command returning 401 with
 * "bad signature".
 */
class SlackBodyCachingFilterTest {

  private final SlackBodyCachingFilter filter = new SlackBodyCachingFilter();

  @Test
  void wrapsSlackCommandPostsSoBodyIsReadableAfterParameterParsing() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/slack/commands");
    req.setContentType("application/x-www-form-urlencoded");
    String body = "token=xyz&team_id=T123&command=%2Farguslog&text=help";
    req.setContent(body.getBytes(StandardCharsets.UTF_8));

    CapturingChain chain = new CapturingChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    HttpServletRequest wrapped = (HttpServletRequest) chain.captured;
    // Simulate the Tomcat-style body consumption: any getParameter access drains the input
    // stream in real containers. After that, the original request's reader would be empty —
    // but the wrapper must still return the cached body bytes.
    wrapped.getParameterMap();
    String readBack = new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(readBack).isEqualTo(body);
  }

  @Test
  void wrapsSlackInteractivityPostsSoBodyIsReadableAfterParameterParsing() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/slack/interactivity");
    req.setContentType("application/x-www-form-urlencoded");
    String body = "payload=%7B%22type%22%3A%22block_actions%22%7D";
    req.setContent(body.getBytes(StandardCharsets.UTF_8));

    CapturingChain chain = new CapturingChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    HttpServletRequest wrapped = (HttpServletRequest) chain.captured;
    wrapped.getParameterMap();
    String readBack = new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(readBack).isEqualTo(body);
  }

  @Test
  void leavesNonSlackPostsAlone() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/issues");
    req.setContent("anything".getBytes(StandardCharsets.UTF_8));

    CapturingChain chain = new CapturingChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.captured).isSameAs(req);
  }

  @Test
  void leavesGetRequestsAlone() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/slack/commands");

    CapturingChain chain = new CapturingChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.captured).isSameAs(req);
  }

  private static class CapturingChain extends MockFilterChain {
    Object captured;

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
        throws IOException, jakarta.servlet.ServletException {
      this.captured = request;
    }
  }
}
