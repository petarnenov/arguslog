package org.arguslog.api.application;

import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.domain.Issue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IssueTriageService implements IssueTriageUseCase {

  private final IssueRepository issues;
  private final MembershipRepository memberships;

  public IssueTriageService(IssueRepository issues, MembershipRepository memberships) {
    this.issues = issues;
    this.memberships = memberships;
  }

  @Override
  @Transactional
  public Optional<Issue> updateStatus(
      long orgId, long projectId, long issueId, Issue.Status status) {
    // Membership for orgId is already enforced by the controller's access guard; we don't
    // re-check here. The repo's findByProjectAndId already returns empty for any project that
    // doesn't belong to the (RLS-pinned) org, so cross-tenant writes are impossible even if a
    // caller fakes a projectId.
    return issues.updateStatus(projectId, issueId, status);
  }

  @Override
  @Transactional
  public Optional<Issue> updateAssignee(
      long orgId, long projectId, long issueId, UUID assigneeUserId) {
    if (assigneeUserId != null) {
      boolean memberOfOrg = memberships.userRoleInOrg(assigneeUserId, orgId).isPresent();
      if (!memberOfOrg) {
        throw new InvalidAssigneeException(
            "Assignee is not a member of this organization. Invite them first.");
      }
    }
    return issues.updateAssignee(projectId, issueId, assigneeUserId);
  }
}
