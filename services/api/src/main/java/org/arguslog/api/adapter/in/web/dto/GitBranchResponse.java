package org.arguslog.api.adapter.in.web.dto;

/**
 * One row in the branch dropdown shown by the "Create release" form. Provider-agnostic shape:
 * {@code name} is the branch / ref name, {@code sha} is the head commit SHA (GitHub calls it {@code
 * sha}, GitLab calls it {@code id} — both are the same git concept and we normalize to {@code sha}
 * here so the UI doesn't need to care which host backed the response).
 */
public record GitBranchResponse(String name, String sha) {}
