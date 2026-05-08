package org.arguslog.api.alerts.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.alerts.application.AlertRuleUseCase.InvalidAlertRuleException;
import org.arguslog.api.alerts.domain.AlertRule;
import org.arguslog.api.auth.adapter.in.web.PatAuthenticationFilter.PatAuthentication;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AlertRuleControllerTest extends AbstractControllerTest {

  @Autowired ObjectMapper mapper;

  @Test
  void listReturnsTheRules() throws Exception {
    when(alertRuleUseCase.list(101L)).thenReturn(List.of(sample(7L, "fatals")));

    mvc.perform(get("/api/v1/projects/101/alert-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].name").value("fatals"))
        .andExpect(jsonPath("$[0].throttleSeconds").value(300))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].conditions.level.in[0]").value("fatal"))
        .andExpect(jsonPath("$[0].actions.destinationIds[0]").value(1));
  }

  @Test
  void postCreatesAndReturns201() throws Exception {
    when(alertRuleUseCase.create(eq(101L), eq("fatals"), any(), any(), eq(300), eq(true)))
        .thenReturn(sample(7L, "fatals"));

    String body =
        """
        { "name": "fatals",
          "conditions": {"level":{"in":["fatal"]}},
          "actions":    {"destinationIds":[1]},
          "throttleSeconds": 300,
          "enabled": true }
        """;
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.name").value("fatals"));
  }

  @Test
  void postOmittingOptionalFieldsAppliesDefaults() throws Exception {
    when(alertRuleUseCase.create(eq(101L), eq("x"), any(), any(), eq(300), eq(true)))
        .thenReturn(sample(7L, "x"));
    String body =
        """
        { "name": "x",
          "conditions": {},
          "actions": {"destinationIds":[1]} }
        """;
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    verify(alertRuleUseCase).create(eq(101L), eq("x"), any(), any(), eq(300), eq(true));
  }

  @Test
  void invalidRuleSurfacesAsProblemJson() throws Exception {
    when(alertRuleUseCase.create(anyLong(), anyString(), any(), any(), anyInt(), anyBoolean()))
        .thenThrow(new InvalidAlertRuleException("conditions.level.in entries must be one of …"));
    String body =
        """
        { "name": "x",
          "conditions": {"level":{"in":["critical"]}},
          "actions": {"destinationIds":[1]} }
        """;
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid alert rule"));
  }

  @Test
  void getOneReturnsTheRow() throws Exception {
    when(alertRuleUseCase.get(101L, 7L)).thenReturn(Optional.of(sample(7L, "fatals")));
    mvc.perform(get("/api/v1/projects/101/alert-rules/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7));
  }

  @Test
  void getOneIs404WhenMissing() throws Exception {
    when(alertRuleUseCase.get(101L, 999L)).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/projects/101/alert-rules/999")).andExpect(status().isNotFound());
  }

  @Test
  void putUpdatesAndReturns200() throws Exception {
    when(alertRuleUseCase.update(eq(101L), eq(7L), eq("renamed"), any(), any(), eq(600), eq(false)))
        .thenReturn(Optional.of(sampleWith(7L, "renamed", 600, false)));
    String body =
        """
        { "name": "renamed",
          "conditions": {},
          "actions": {"destinationIds":[1]},
          "throttleSeconds": 600,
          "enabled": false }
        """;
    mvc.perform(
            put("/api/v1/projects/101/alert-rules/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("renamed"))
        .andExpect(jsonPath("$.throttleSeconds").value(600))
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  void deleteReturns204() throws Exception {
    when(alertRuleUseCase.delete(101L, 7L)).thenReturn(true);
    mvc.perform(delete("/api/v1/projects/101/alert-rules/7")).andExpect(status().isNoContent());
  }

  @Test
  void deleteIs404WhenMissing() throws Exception {
    when(alertRuleUseCase.delete(101L, 999L)).thenReturn(false);
    mvc.perform(delete("/api/v1/projects/101/alert-rules/999")).andExpect(status().isNotFound());
  }

  @Test
  void postWithPatLackingAlertsWriteIs403() throws Exception {
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .with(authentication(patWith(PatScope.ALERTS_READ)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"conditions\":{},\"actions\":{\"destinationIds\":[1]}}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void putWithPatLackingAlertsWriteIs403() throws Exception {
    mvc.perform(
            put("/api/v1/projects/101/alert-rules/7")
                .with(authentication(patWith(PatScope.ALERTS_READ)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"conditions\":{},\"actions\":{\"destinationIds\":[1]}}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteWithPatLackingAlertsWriteIs403() throws Exception {
    mvc.perform(
            delete("/api/v1/projects/101/alert-rules/7")
                .with(authentication(patWith(PatScope.ISSUES_READ))))
        .andExpect(status().isForbidden());
  }

  private static PatAuthentication patWith(PatScope... scopes) {
    return new PatAuthentication(
        new PersonalAccessToken(
            7L,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "ci",
            "ABCDEFGH",
            null,
            null,
            Instant.parse("2026-05-05T12:00:00Z"),
            EnumSet.copyOf(List.of(scopes))));
  }

  private AlertRule sample(long id, String name) {
    return sampleWith(id, name, 300, true);
  }

  private AlertRule sampleWith(long id, String name, int throttle, boolean enabled) {
    try {
      return new AlertRule(
          id,
          101L,
          name,
          mapper.readTree("{\"level\":{\"in\":[\"fatal\"]}}"),
          mapper.readTree("{\"destinationIds\":[1]}"),
          throttle,
          enabled,
          Instant.parse("2026-05-05T12:00:00Z"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
