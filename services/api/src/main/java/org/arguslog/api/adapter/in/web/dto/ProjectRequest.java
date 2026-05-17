package org.arguslog.api.adapter.in.web.dto;

/**
 * Create payload for {@code POST /api/v1/orgs/{orgId}/projects}. The Git link is optional; supply
 * both {@code gitProvider} ({@code "github"} | {@code "gitlab"}) and {@code gitRepo} together, or
 * leave both null. The service layer normalizes common paste shapes (full URLs, SSH clone strings,
 * {@code .git} suffix) before storing.
 */
public record ProjectRequest(String name, String platform, String gitProvider, String gitRepo) {}
