package org.arguslog.api.adapter.in.web.dto;

/**
 * Body for {@code PATCH /api/v1/projects/{projectId}/issues/{issueId}/ai-analysis}.
 *
 * @param analysis markdown body, the agent's root-cause hypothesis + suggested fix
 * @param model self-reported model id (e.g. {@code claude-opus-4-7}); stored alongside the body so
 *     a future „re-run with newer model" affordance can see what was used.
 */
public record IssueAiAnalysisRequest(String analysis, String model) {}
