package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.arguslog.api.application.CursorCodec.InvalidCursorException;
import org.arguslog.api.application.CursorCodec.LongCursor;
import org.arguslog.api.application.ListIssuesUseCase.Page;
import org.arguslog.api.application.ListIssuesUseCase.Query;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListIssuesServiceTest {

  @Mock IssueRepository repository;

  ListIssuesService service;

  @BeforeEach
  void setUp() {
    service = new ListIssuesService(repository);
  }

  @Test
  void emptyResultMeansNoNextCursor() {
    when(repository.page(eq(101L), any(), any(), any(), anyInt())).thenReturn(List.of());
    Page page =
        service.list(new Query(101L, Optional.empty(), Optional.empty(), Optional.empty(), 50));
    assertThat(page.issues()).isEmpty();
    assertThat(page.nextCursor()).isEmpty();
  }

  @Test
  void resultsBelowLimitMeanNoNextCursor() {
    when(repository.page(anyLong(), any(), any(), any(), anyInt())).thenReturn(issues(3));
    Page page =
        service.list(new Query(101L, Optional.empty(), Optional.empty(), Optional.empty(), 50));
    assertThat(page.issues()).hasSize(3);
    assertThat(page.nextCursor()).isEmpty();
  }

  @Test
  void resultsExceedingLimitTrimAndEmitNextCursor() {
    // limit=2 → service requests 3, gets 3 → trim to 2 + emit cursor for the 2nd
    when(repository.page(anyLong(), any(), any(), any(), anyInt())).thenReturn(issues(3));
    Page page =
        service.list(new Query(101L, Optional.empty(), Optional.empty(), Optional.empty(), 2));
    assertThat(page.issues()).hasSize(2);
    assertThat(page.nextCursor()).isPresent();
    LongCursor decoded = CursorCodec.decodeLong(page.nextCursor().orElseThrow());
    assertThat(decoded.id()).isEqualTo(page.issues().get(1).id());
  }

  @Test
  void cursorIsDecodedAndForwardedToRepository() {
    LongCursor cursor = new LongCursor(Instant.parse("2026-05-05T12:00:00Z"), 42L);
    String encoded = CursorCodec.encodeLong(cursor.instant(), cursor.id());
    when(repository.page(anyLong(), any(), any(), any(), anyInt())).thenReturn(List.of());

    service.list(new Query(101L, Optional.empty(), Optional.empty(), Optional.of(encoded), 50));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<LongCursor>> captor = ArgumentCaptor.forClass(Optional.class);
    verify(repository).page(eq(101L), any(), any(), captor.capture(), eq(51));
    assertThat(captor.getValue()).contains(cursor);
  }

  @Test
  void cursorRoundTripsExactly() {
    LongCursor original = new LongCursor(Instant.parse("2026-05-05T12:34:56.789Z"), 12345L);
    LongCursor parsed =
        CursorCodec.decodeLong(CursorCodec.encodeLong(original.instant(), original.id()));
    assertThat(parsed).isEqualTo(original);
  }

  @Test
  void brokenCursorsAreRejectedClearly() {
    assertThatThrownBy(() -> CursorCodec.decodeLong("not-base64!!"))
        .isInstanceOf(InvalidCursorException.class);
    String noSeparator =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("missing-separator".getBytes());
    assertThatThrownBy(() -> CursorCodec.decodeLong(noSeparator))
        .isInstanceOf(InvalidCursorException.class);
    String badInstant =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("not-a-time|123".getBytes());
    assertThatThrownBy(() -> CursorCodec.decodeLong(badInstant))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void limitIsClampedToMax() {
    Query q = new Query(101L, Optional.empty(), Optional.empty(), Optional.empty(), 9999);
    assertThat(q.limit()).isEqualTo(Query.MAX_LIMIT);
  }

  @Test
  void nonPositiveLimitFallsBackToDefault() {
    Query a = new Query(101L, Optional.empty(), Optional.empty(), Optional.empty(), 0);
    Query b = new Query(101L, Optional.empty(), Optional.empty(), Optional.empty(), -1);
    assertThat(a.limit()).isEqualTo(Query.DEFAULT_LIMIT);
    assertThat(b.limit()).isEqualTo(Query.DEFAULT_LIMIT);
  }

  private static List<Issue> issues(int n) {
    return IntStream.rangeClosed(1, n)
        .mapToObj(
            i ->
                new Issue(
                    i,
                    101L,
                    "fp-" + i,
                    Issue.Status.UNRESOLVED,
                    Issue.Level.ERROR,
                    "Title " + i,
                    null,
                    Instant.parse("2026-05-05T12:00:00Z").plusSeconds(-i),
                    Instant.parse("2026-05-05T13:00:00Z").plusSeconds(-i),
                    1L))
        .toList();
  }
}
