package org.arguslog.api.adapter.in.web.dto;

/** Body for {@code PATCH /api/v1/projects/{projectId}/issues/{issueId}}. */
public record IssueStatusRequest(String status) {}
