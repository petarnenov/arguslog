package org.arguslog.api.adapter.in.web.dto;

import java.util.UUID;

/**
 * Body for {@code PATCH /api/v1/projects/{projectId}/issues/{issueId}/assignee}. {@code userId =
 * null} (or absent) unassigns the issue.
 */
public record IssueAssigneeRequest(UUID userId) {}
