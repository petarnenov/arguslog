package org.arguslog.api.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.CursorCodec.InvalidCursorException;
import org.arguslog.api.application.ListIssueEventsUseCase;
import org.arguslog.api.application.ListIssuesUseCase;
import org.arguslog.api.domain.Event;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class IssueControllerTest extends AbstractControllerTest {

  @Autowired ObjectMapper json;

  // ── list ─────────────────────────────────────────────────────────────────

  @Test
  void returnsPaginatedEnvelope() throws Exception {
    when(listIssuesUseCase.list(any()))
        .thenReturn(new ListIssuesUseCase.Page(List.of(sampleIssue(7L)), Optional.of("Mi4yLjI=")));

    mvc.perform(get("/api/v1/projects/101/issues").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].id").value(7))
        .andExpect(jsonPath("$.data[0].projectId").value(101))
        .andExpect(jsonPath("$.data[0].fingerprint").value("fp-x"))
        .andExpect(jsonPath("$.data[0].status").value("unresolved"))
        .andExpect(jsonPath("$.data[0].level").value("error"))
        .andExpect(jsonPath("$.data[0].title").value("TypeError: x"))
        .andExpect(jsonPath("$.data[0].culprit").value("render at app.js:42"))
        .andExpect(jsonPath("$.data[0].occurrenceCount").value(3))
        .andExpect(jsonPath("$.page.next").value("Mi4yLjI="));
  }

  @Test
  void omitsNextWhenLastPage() throws Exception {
    when(listIssuesUseCase.list(any()))
        .thenReturn(new ListIssuesUseCase.Page(List.of(), Optional.empty()));
    mvc.perform(get("/api/v1/projects/101/issues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(0)))
        .andExpect(jsonPath("$.page.next").doesNotExist());
  }

  @Test
  void invalidCursorIsRejectedAsProblemJson() throws Exception {
    when(listIssuesUseCase.list(any()))
        .thenThrow(new InvalidCursorException("cursor missing separator"));
    mvc.perform(get("/api/v1/projects/101/issues?cursor=garbage"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid cursor"));
  }

  @Test
  void invalidStatusIsRejectedAsProblemJson() throws Exception {
    mvc.perform(get("/api/v1/projects/101/issues?status=bogus"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid filter"));
  }

  @Test
  void invalidLevelIsRejectedAsProblemJson() throws Exception {
    mvc.perform(get("/api/v1/projects/101/issues?level=critical"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid filter"));
  }

  // ── get one ──────────────────────────────────────────────────────────────

  @Test
  void issueDetailReturnsTheRow() throws Exception {
    when(getIssueUseCase.get(101L, 7L)).thenReturn(Optional.of(sampleIssue(7L)));
    mvc.perform(get("/api/v1/projects/101/issues/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.projectId").value(101))
        .andExpect(jsonPath("$.title").value("TypeError: x"));
  }

  @Test
  void unknownIssueIs404ProblemJson() throws Exception {
    when(getIssueUseCase.get(eq(101L), eq(999L))).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/projects/101/issues/999"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Not found"));
  }

  // ── events ───────────────────────────────────────────────────────────────

  @Test
  void issueEventsReturnsEnvelopeWithPayloadPassedThrough() throws Exception {
    UUID eventId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    when(getIssueUseCase.get(101L, 7L)).thenReturn(Optional.of(sampleIssue(7L)));
    when(listIssueEventsUseCase.list(any()))
        .thenReturn(
            new ListIssueEventsUseCase.Page(
                List.of(
                    new Event(
                        eventId,
                        7L,
                        101L,
                        Instant.parse("2026-05-05T12:00:00Z"),
                        json.readTree("{\"level\":\"error\",\"message\":\"boom\"}"))),
                Optional.empty()));

    mvc.perform(get("/api/v1/projects/101/issues/7/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].id").value(eventId.toString()))
        .andExpect(jsonPath("$.data[0].issueId").value(7))
        .andExpect(jsonPath("$.data[0].projectId").value(101))
        .andExpect(jsonPath("$.data[0].payload.level").value("error"))
        .andExpect(jsonPath("$.data[0].payload.message").value("boom"))
        .andExpect(jsonPath("$.page.next").doesNotExist());
  }

  @Test
  void issueEventsForUnknownIssueIs404() throws Exception {
    when(getIssueUseCase.get(101L, 999L)).thenReturn(Optional.empty());
    when(listIssueEventsUseCase.list(any()))
        .thenReturn(new ListIssueEventsUseCase.Page(List.of(), Optional.empty()));
    mvc.perform(get("/api/v1/projects/101/issues/999/events"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Not found"));
  }

  private static Issue sampleIssue(long id) {
    return new Issue(
        id,
        101L,
        "fp-x",
        Issue.Status.UNRESOLVED,
        Issue.Level.ERROR,
        "TypeError: x",
        "render at app.js:42",
        Instant.parse("2026-05-05T10:00:00Z"),
        Instant.parse("2026-05-05T11:00:00Z"),
        3L,
        null);
  }
}
