package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetIssueServiceTest {

  @Mock IssueRepository repository;

  @Test
  void delegatesToRepository() {
    Issue sample =
        new Issue(
            7L,
            101L,
            "fp",
            Issue.Status.UNRESOLVED,
            Issue.Level.ERROR,
            "x",
            null,
            Instant.now(),
            Instant.now(),
            1L);
    when(repository.findByProjectAndId(101L, 7L)).thenReturn(Optional.of(sample));
    assertThat(new GetIssueService(repository).get(101L, 7L)).contains(sample);
  }

  @Test
  void emptyWhenRepositoryReturnsEmpty() {
    when(repository.findByProjectAndId(101L, 999L)).thenReturn(Optional.empty());
    assertThat(new GetIssueService(repository).get(101L, 999L)).isEmpty();
  }
}
