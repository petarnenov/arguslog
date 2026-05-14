package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssuesByReleaseUseCaseTest {

  @Mock IssueRepository issues;
  @Mock ReleaseRepository releases;

  IssuesByReleaseUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new IssuesByReleaseUseCase(issues, releases);
  }

  @Test
  void returnsTheIssuesFromTheRepoWhenReleaseExists() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "v1.0.0", Instant.now())));
    Issue issue =
        new Issue(
            42L,
            101L,
            "fp",
            Issue.Status.UNRESOLVED,
            Issue.Level.ERROR,
            "boom",
            null,
            Instant.now(),
            Instant.now(),
            1L,
            null,
            7L,
            "v1.0.0");
    when(issues.listIntroducedInRelease(101L, 7L, IssuesByReleaseUseCase.LIMIT))
        .thenReturn(List.of(issue));

    List<Issue> out = useCase.list(101L, 7L);

    assertThat(out).containsExactly(issue);
    verify(issues).listIntroducedInRelease(101L, 7L, IssuesByReleaseUseCase.LIMIT);
  }

  @Test
  void emptyListIsTheNoRegressionsCase() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "v1.0.0", Instant.now())));
    when(issues.listIntroducedInRelease(101L, 7L, IssuesByReleaseUseCase.LIMIT))
        .thenReturn(List.of());

    assertThat(useCase.list(101L, 7L)).isEmpty();
  }

  @Test
  void unknownReleaseRaisesNotFoundWithoutHittingIssueRepo() {
    when(releases.find(101L, 9999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.list(101L, 9999L))
        .isInstanceOf(IssuesByReleaseUseCase.ReleaseNotFoundException.class)
        .hasMessageContaining("9999")
        .hasMessageContaining("101");
    verify(issues, never())
        .listIntroducedInRelease(101L, 9999L, IssuesByReleaseUseCase.LIMIT);
  }
}
