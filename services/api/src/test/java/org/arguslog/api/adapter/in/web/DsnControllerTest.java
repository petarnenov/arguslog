package org.arguslog.api.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.arguslog.api.application.DsnUseCase.DsnAlreadyRevokedException;
import org.arguslog.api.application.DsnUseCase.DsnNotFoundException;
import org.arguslog.api.domain.Dsn;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;

class DsnControllerTest extends AbstractControllerTest {

  private static final long PROJECT_ID = 9L;

  @Test
  void listReturnsSummariesWithoutTheFullDsnString() throws Exception {
    when(dsnUseCase.list(PROJECT_ID))
        .thenReturn(
            List.of(
                new Dsn(101L, PROJECT_ID, "PUBKEY1", true, Instant.parse("2026-05-01T00:00:00Z")),
                new Dsn(100L, PROJECT_ID, "PUBKEY0", true, Instant.parse("2026-04-01T00:00:00Z"))));

    mvc.perform(get("/api/v1/projects/{projectId}/keys", PROJECT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].id").value(101))
        .andExpect(jsonPath("$[0].dsnPublic").value("PUBKEY1"))
        .andExpect(jsonPath("$[0].active").value(true))
        // The full DSN string is intentionally absent — only POST returns it.
        .andExpect(jsonPath("$[0].dsn").doesNotExist())
        .andExpect(jsonPath("$[1].dsn").doesNotExist());

    // Default call goes through list(), NOT listAll(); SDK-facing surfaces shouldn't see revoked
    // rows by accident.
    verify(dsnUseCase).list(PROJECT_ID);
  }

  @Test
  void listWithIncludeRevokedReturnsActiveAndRevokedRows() throws Exception {
    when(dsnUseCase.listAll(PROJECT_ID))
        .thenReturn(
            List.of(
                new Dsn(101L, PROJECT_ID, "ACTIVE", true, Instant.parse("2026-05-01T00:00:00Z")),
                new Dsn(100L, PROJECT_ID, "REVOKED", false, Instant.parse("2026-04-01T00:00:00Z"))));

    mvc.perform(get("/api/v1/projects/{projectId}/keys?includeRevoked=true", PROJECT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].active").value(true))
        .andExpect(jsonPath("$[1].active").value(false));

    verify(dsnUseCase).listAll(PROJECT_ID);
  }

  @Test
  void createReturnsTheFullDsnStringExactlyOnce() throws Exception {
    when(dsnUseCase.create(PROJECT_ID))
        .thenReturn(
            new Dsn(101L, PROJECT_ID, "FRESHKEY", true, Instant.parse("2026-05-08T00:00:00Z")));

    mvc.perform(post("/api/v1/projects/{projectId}/keys", PROJECT_ID))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(101))
        .andExpect(jsonPath("$.dsnPublic").value("FRESHKEY"))
        .andExpect(jsonPath("$.dsn").value("arguslog://FRESHKEY@localhost:8080/api/9"));
  }

  @Test
  void revokeReturnsNoContentAndDelegatesToTheUseCase() throws Exception {
    when(dsnUseCase.revoke(PROJECT_ID, 101L))
        .thenReturn(
            new Dsn(101L, PROJECT_ID, "PUBKEY1", false, Instant.parse("2026-05-01T00:00:00Z")));

    mvc.perform(delete("/api/v1/projects/{projectId}/keys/{keyId}", PROJECT_ID, 101L))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(dsnUseCase).revoke(eq(PROJECT_ID), eq(101L));
  }

  @Test
  void revokeReturns404ProblemWhenKeyMissing() throws Exception {
    doThrow(new DsnNotFoundException(PROJECT_ID, 999L)).when(dsnUseCase).revoke(PROJECT_ID, 999L);

    mvc.perform(delete("/api/v1/projects/{projectId}/keys/{keyId}", PROJECT_ID, 999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://arguslog.org/problems/dsn-not-found"))
        .andExpect(jsonPath("$.title").value("DSN not found"));
  }

  @Test
  void revokeReturns409ProblemWhenAlreadyRevoked() throws Exception {
    doThrow(new DsnAlreadyRevokedException(101L)).when(dsnUseCase).revoke(PROJECT_ID, 101L);

    mvc.perform(delete("/api/v1/projects/{projectId}/keys/{keyId}", PROJECT_ID, 101L))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://arguslog.org/problems/dsn-already-revoked"))
        .andExpect(jsonPath("$.title").value("DSN already revoked"));
  }
}
