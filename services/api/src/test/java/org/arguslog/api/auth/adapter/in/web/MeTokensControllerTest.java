package org.arguslog.api.auth.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.auth.application.PatUseCase.InvalidPatException;
import org.arguslog.api.auth.application.PatUseCase.Issued;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

class MeTokensControllerTest extends AbstractControllerTest {

  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-05-05T12:00:00Z");

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void postReturnsPlaintextOnceWithCreatedRow() throws Exception {
    PersonalAccessToken stored =
        new PersonalAccessToken(7L, USER, "ci-bot", "ABCDEFGH", null, null, NOW, null);
    when(patUseCase.create(eq(USER), eq("ci-bot"), isNull(), isNull()))
        .thenReturn(new Issued(stored, "arglog_pat_ABCDEFGH_" + "x".repeat(48)));

    mvc.perform(
            post("/api/v1/me/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci-bot\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.prefix").value("ABCDEFGH"))
        .andExpect(jsonPath("$.token").value(startsWith("arglog_pat_ABCDEFGH_")));
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void postWithScopesPlumbsThemThroughAndEchoesOnResponse() throws Exception {
    Set<PatScope> requested = EnumSet.of(PatScope.RELEASES_WRITE, PatScope.SOURCEMAPS_WRITE);
    PersonalAccessToken stored =
        new PersonalAccessToken(8L, USER, "ci-bot", "ABCDEFGH", null, null, NOW, requested);
    when(patUseCase.create(eq(USER), eq("ci-bot"), isNull(), eq(requested)))
        .thenReturn(new Issued(stored, "arglog_pat_ABCDEFGH_" + "x".repeat(48)));

    mvc.perform(
            post("/api/v1/me/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ci-bot\",\"scopes\":[\"releases:write\",\"sourcemaps:write\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.scopes[0]").value("releases:write"))
        .andExpect(jsonPath("$.scopes[1]").value("sourcemaps:write"));
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void postWithUnknownScopeReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/me/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci-bot\",\"scopes\":[\"galaxy:nuke\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(startsWith("Unknown scope")));
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void getListOmitsTheTokenField() throws Exception {
    when(patUseCase.list(USER))
        .thenReturn(
            List.of(
                new PersonalAccessToken(7L, USER, "ci-bot", "ABCDEFGH", null, null, NOW, null)));

    mvc.perform(get("/api/v1/me/tokens"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].prefix").value("ABCDEFGH"))
        .andExpect(jsonPath("$[0].token").doesNotExist());
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void deleteReturns204WhenRevoked() throws Exception {
    when(patUseCase.revoke(USER, 7L)).thenReturn(true);
    mvc.perform(delete("/api/v1/me/tokens/7")).andExpect(status().isNoContent());
    verify(patUseCase).revoke(USER, 7L);
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void deleteReturns404WhenMissing() throws Exception {
    when(patUseCase.revoke(USER, 999L)).thenReturn(false);
    mvc.perform(delete("/api/v1/me/tokens/999")).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void blankNameReturns400ProblemJson() throws Exception {
    when(patUseCase.create(eq(USER), eq(""), any(), any()))
        .thenThrow(new InvalidPatException("name is required"));

    mvc.perform(
            post("/api/v1/me/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value(startsWith("Invalid")));
  }
}
