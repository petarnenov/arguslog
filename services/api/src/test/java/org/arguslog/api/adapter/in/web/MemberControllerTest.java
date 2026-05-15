package org.arguslog.api.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.arguslog.api.domain.Member;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;

/**
 * Covers GET listing + JSON shape. POST/PATCH/DELETE need a JWT subject which the test profile
 * (permitAll, no OAuth2) doesn't populate, and there's no controller-test pattern for that in this
 * codebase — service-level coverage in {@code MemberServiceTest} exercises every mutating path.
 * Same convention as {@code OrgController} / {@code ProjectController} which are untested at
 * controller level.
 */
class MemberControllerTest extends AbstractControllerTest {

  static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void listReturnsAllMembers() throws Exception {
    when(memberUseCase.list(1L))
        .thenReturn(
            List.of(
                new Member(
                    USER,
                    "alice@example.com",
                    "Alice",
                    "owner",
                    Instant.parse("2026-05-08T00:00:00Z"),
                    false)));

    mvc.perform(get("/api/v1/orgs/1/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].userId").value(USER.toString()))
        .andExpect(jsonPath("$[0].email").value("alice@example.com"))
        .andExpect(jsonPath("$[0].displayName").value("Alice"))
        .andExpect(jsonPath("$[0].role").value("owner"))
        .andExpect(jsonPath("$[0].addedAt").value("2026-05-08T00:00:00Z"))
        .andExpect(jsonPath("$[0].pending").value(false));
  }

  @Test
  void listMarksUnseenUsersAsPending() throws Exception {
    when(memberUseCase.list(1L))
        .thenReturn(
            List.of(
                new Member(
                    USER,
                    "invited@example.com",
                    null,
                    "member",
                    Instant.parse("2026-05-13T00:00:00Z"),
                    true)));

    mvc.perform(get("/api/v1/orgs/1/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].pending").value(true))
        .andExpect(jsonPath("$[0].email").value("invited@example.com"));
  }

  @Test
  void listEmptyReturnsEmptyArray() throws Exception {
    when(memberUseCase.list(1L)).thenReturn(List.of());
    mvc.perform(get("/api/v1/orgs/1/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }
}
