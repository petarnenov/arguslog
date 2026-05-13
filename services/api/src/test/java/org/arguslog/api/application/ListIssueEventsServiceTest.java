package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.arguslog.api.application.CursorCodec.UuidCursor;
import org.arguslog.api.application.ListIssueEventsUseCase.Page;
import org.arguslog.api.application.ListIssueEventsUseCase.Query;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Event;
import org.arguslog.api.domain.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListIssueEventsServiceTest {

  @Mock IssueRepository issues;
  @Mock EventRepository events;

  ListIssueEventsService service;
  Issue knownIssue;

  @BeforeEach
  void setUp() {
    service = new ListIssueEventsService(issues, events);
    knownIssue =
        new Issue(
            7L,
            101L,
            "fp",
            Issue.Status.UNRESOLVED,
            Issue.Level.ERROR,
            "TypeError",
            null,
            Instant.now(),
            Instant.now(),
            1L,
            null);
  }

  @Test
  void unknownIssueReturnsEmptyPageWithoutHittingTheEventsTable() {
    when(issues.findByProjectAndId(101L, 999L)).thenReturn(Optional.empty());

    Page page = service.list(new Query(101L, 999L, Optional.empty(), 50));

    assertThat(page.events()).isEmpty();
    assertThat(page.nextCursor()).isEmpty();
    verify(events, never()).page(anyLong(), any(), anyInt());
  }

  @Test
  void belowLimitMeansNoNextCursor() {
    when(issues.findByProjectAndId(101L, 7L)).thenReturn(Optional.of(knownIssue));
    when(events.page(anyLong(), any(), anyInt())).thenReturn(events(3));

    Page page = service.list(new Query(101L, 7L, Optional.empty(), 50));

    assertThat(page.events()).hasSize(3);
    assertThat(page.nextCursor()).isEmpty();
  }

  @Test
  void overLimitTrimsAndEmitsCursorPointingToTheLastReturnedEvent() {
    when(issues.findByProjectAndId(101L, 7L)).thenReturn(Optional.of(knownIssue));
    when(events.page(anyLong(), any(), anyInt())).thenReturn(events(3));

    Page page = service.list(new Query(101L, 7L, Optional.empty(), 2));

    assertThat(page.events()).hasSize(2);
    assertThat(page.nextCursor()).isPresent();
    UuidCursor decoded = CursorCodec.decodeUuid(page.nextCursor().orElseThrow());
    assertThat(decoded.id()).isEqualTo(page.events().get(1).id());
  }

  private static List<Event> events(int n) {
    return IntStream.rangeClosed(1, n)
        .mapToObj(
            i ->
                new Event(
                    UUID.fromString("00000000-0000-4000-8000-00000000000" + i),
                    7L,
                    101L,
                    Instant.parse("2026-05-05T12:00:00Z").plusSeconds(-i),
                    JsonNodeFactory.instance.objectNode().put("level", "error")))
        .toList();
  }
}
