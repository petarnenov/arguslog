package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.IssueTriageUseCase.InvalidAiAnalysisException;
import org.arguslog.api.application.IssueTriageUseCase.InvalidAssigneeException;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.domain.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueTriageServiceTest {

  @Mock IssueRepository issues;
  @Mock MembershipRepository memberships;
  IssueTriageService service;

  static final long ORG_ID = 1L;
  static final long PROJECT_ID = 101L;
  static final long ISSUE_ID = 7L;
  static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID OUTSIDER = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @BeforeEach
  void setUp() {
    service = new IssueTriageService(issues, memberships);
  }

  @Test
  void updateStatusDelegatesToRepo() {
    Issue updated = sample(Issue.Status.RESOLVED, null);
    when(issues.updateStatus(PROJECT_ID, ISSUE_ID, Issue.Status.RESOLVED))
        .thenReturn(Optional.of(updated));

    assertThat(service.updateStatus(ORG_ID, PROJECT_ID, ISSUE_ID, Issue.Status.RESOLVED))
        .contains(updated);
  }

  @Test
  void updateStatusReturnsEmptyWhenRepoMisses() {
    when(issues.updateStatus(PROJECT_ID, ISSUE_ID, Issue.Status.RESOLVED))
        .thenReturn(Optional.empty());
    assertThat(service.updateStatus(ORG_ID, PROJECT_ID, ISSUE_ID, Issue.Status.RESOLVED)).isEmpty();
  }

  @Test
  void updateAssigneeAllowsOrgMember() {
    Issue updated = sample(Issue.Status.UNRESOLVED, MEMBER);
    when(memberships.userRoleInOrg(MEMBER, ORG_ID)).thenReturn(Optional.of("member"));
    when(issues.updateAssignee(PROJECT_ID, ISSUE_ID, MEMBER)).thenReturn(Optional.of(updated));

    assertThat(service.updateAssignee(ORG_ID, PROJECT_ID, ISSUE_ID, MEMBER)).contains(updated);
  }

  @Test
  void updateAssigneeRejectsNonMember() {
    when(memberships.userRoleInOrg(OUTSIDER, ORG_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateAssignee(ORG_ID, PROJECT_ID, ISSUE_ID, OUTSIDER))
        .isInstanceOf(InvalidAssigneeException.class)
        .hasMessageContaining("not a member");
    verify(issues, never()).updateAssignee(anyLong(), anyLong(), any());
  }

  @Test
  void updateAssigneeUnassignsWithoutMembershipCheck() {
    Issue updated = sample(Issue.Status.UNRESOLVED, null);
    when(issues.updateAssignee(PROJECT_ID, ISSUE_ID, null)).thenReturn(Optional.of(updated));

    assertThat(service.updateAssignee(ORG_ID, PROJECT_ID, ISSUE_ID, null)).contains(updated);
    verify(memberships, never()).userRoleInOrg(any(), anyLong());
  }

  @Test
  void attachAiAnalysisDelegatesToRepo() {
    Issue updated = sample(Issue.Status.UNRESOLVED, null);
    when(issues.updateAiAnalysis(PROJECT_ID, ISSUE_ID, "**root cause**", "claude-opus-4-7"))
        .thenReturn(Optional.of(updated));

    assertThat(
            service.attachAiAnalysis(
                ORG_ID, PROJECT_ID, ISSUE_ID, "**root cause**", "claude-opus-4-7"))
        .contains(updated);
  }

  @Test
  void attachAiAnalysisRejectsBlankBody() {
    assertThatThrownBy(
            () -> service.attachAiAnalysis(ORG_ID, PROJECT_ID, ISSUE_ID, "   ", "claude-opus-4-7"))
        .isInstanceOf(InvalidAiAnalysisException.class)
        .hasMessageContaining("must not be empty");
    verify(issues, never()).updateAiAnalysis(anyLong(), anyLong(), any(), any());
  }

  @Test
  void attachAiAnalysisRejectsOversizedBody() {
    String huge = "x".repeat(32 * 1024 + 1);
    assertThatThrownBy(
            () -> service.attachAiAnalysis(ORG_ID, PROJECT_ID, ISSUE_ID, huge, "claude-opus-4-7"))
        .isInstanceOf(InvalidAiAnalysisException.class)
        .hasMessageContaining("exceeds");
    verify(issues, never()).updateAiAnalysis(anyLong(), anyLong(), any(), any());
  }

  @Test
  void attachAiAnalysisRejectsBlankModel() {
    assertThatThrownBy(
            () -> service.attachAiAnalysis(ORG_ID, PROJECT_ID, ISSUE_ID, "analysis", ""))
        .isInstanceOf(InvalidAiAnalysisException.class)
        .hasMessageContaining("model");
  }

  private static Issue sample(Issue.Status status, UUID assignee) {
    return new Issue(
        ISSUE_ID,
        PROJECT_ID,
        "fp",
        status,
        Issue.Level.ERROR,
        "Title",
        null,
        Instant.parse("2026-05-13T00:00:00Z"),
        Instant.parse("2026-05-13T00:00:00Z"),
        1L,
        assignee);
  }
}
