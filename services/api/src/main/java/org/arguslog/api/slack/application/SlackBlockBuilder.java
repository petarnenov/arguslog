package org.arguslog.api.slack.application;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.arguslog.api.domain.Issue;

/**
 * Builds Block Kit JSON for Slack replies. Block Kit is structured JSON
 * (https://api.slack.com/reference/block-kit/blocks); we emit it as nested maps so Jackson
 * serializes verbatim without needing a typed POJO tree.
 *
 * <p>The dashboard link in every block is what makes the slash command useful — chat surface
 * for read-at-a-glance, click-through to the full UI for triage actions that need context.
 */
public final class SlackBlockBuilder {

  private final String dashboardBaseUrl;

  public SlackBlockBuilder(String dashboardBaseUrl) {
    this.dashboardBaseUrl = stripTrailingSlash(dashboardBaseUrl);
  }

  /** Top-N issue list reply. orgSlug is needed to build the dashboard link. */
  public List<Map<String, Object>> issuesList(String orgSlug, long projectId, List<Issue> issues) {
    if (issues.isEmpty()) {
      return List.of(section("No unresolved issues. ✨"));
    }
    List<Map<String, Object>> blocks = new ArrayList<>();
    blocks.add(section("*Top " + issues.size() + " unresolved issues*"));
    blocks.add(divider());
    for (Issue i : issues) {
      String title = truncate(i.title(), 200);
      String culprit = i.culprit() == null ? "" : " · `" + truncate(i.culprit(), 80) + "`";
      String url =
          dashboardBaseUrl
              + "/orgs/"
              + orgSlug
              + "/projects/"
              + projectId
              + "/issues/"
              + i.id();
      String body =
          "*<"
              + url
              + "|"
              + escape(title)
              + ">*"
              + culprit
              + "\n"
              + i.level().dbValue().toUpperCase()
              + " · "
              + i.occurrenceCount()
              + " events · last seen "
              + DateTimeFormatter.ISO_INSTANT.format(i.lastSeenAt());
      blocks.add(section(body));
    }
    return blocks;
  }

  public List<Map<String, Object>> issueDetail(String orgSlug, Issue issue) {
    String url =
        dashboardBaseUrl
            + "/orgs/"
            + orgSlug
            + "/projects/"
            + issue.projectId()
            + "/issues/"
            + issue.id();
    StringBuilder body = new StringBuilder();
    body.append("*<").append(url).append("|").append(escape(issue.title())).append(">*\n");
    body.append("`").append(issue.status().dbValue()).append("` · `");
    body.append(issue.level().dbValue()).append("` · ");
    body.append(issue.occurrenceCount()).append(" events\n");
    if (issue.culprit() != null) {
      body.append("Culprit: `").append(escape(issue.culprit())).append("`\n");
    }
    body.append("First seen: ").append(DateTimeFormatter.ISO_INSTANT.format(issue.firstSeenAt()));
    if (issue.firstSeenReleaseVersion() != null) {
      body.append(" in `").append(escape(issue.firstSeenReleaseVersion())).append("`");
    }
    body.append("\nLast seen: ").append(DateTimeFormatter.ISO_INSTANT.format(issue.lastSeenAt()));
    return List.of(section(body.toString()));
  }

  public List<Map<String, Object>> resolvedConfirmation(String orgSlug, Issue issue) {
    return statusConfirmation(orgSlug, issue, "✅ Resolved");
  }

  public List<Map<String, Object>> ignoredConfirmation(String orgSlug, Issue issue) {
    return statusConfirmation(orgSlug, issue, "🔕 Ignored");
  }

  public List<Map<String, Object>> reopenedConfirmation(String orgSlug, Issue issue) {
    return statusConfirmation(orgSlug, issue, "🔄 Reopened");
  }

  private List<Map<String, Object>> statusConfirmation(String orgSlug, Issue issue, String prefix) {
    String url =
        dashboardBaseUrl
            + "/orgs/"
            + orgSlug
            + "/projects/"
            + issue.projectId()
            + "/issues/"
            + issue.id();
    return List.of(
        section(
            prefix
                + " <"
                + url
                + "|"
                + escape(truncate(issue.title(), 200))
                + "> (#"
                + issue.id()
                + ")"));
  }

  public List<Map<String, Object>> releaseIssues(
      String orgSlug, long projectId, String version, List<Issue> issues) {
    if (issues.isEmpty()) {
      return List.of(section("Clean ship — no new issues attributed to `" + escape(version) + "`."));
    }
    List<Map<String, Object>> blocks = new ArrayList<>();
    blocks.add(
        section(
            "*"
                + issues.size()
                + " issue"
                + (issues.size() == 1 ? "" : "s")
                + " first seen in `"
                + escape(version)
                + "`*"));
    blocks.add(divider());
    for (Issue i : issues) {
      String url =
          dashboardBaseUrl
              + "/orgs/"
              + orgSlug
              + "/projects/"
              + projectId
              + "/issues/"
              + i.id();
      blocks.add(
          section(
              "*<"
                  + url
                  + "|"
                  + escape(truncate(i.title(), 200))
                  + ">*\n"
                  + i.level().dbValue().toUpperCase()
                  + " · "
                  + i.occurrenceCount()
                  + " events"));
    }
    return blocks;
  }

  public List<Map<String, Object>> help() {
    String body =
        "*`/arguslog` — error tracking from chat*\n"
            + "• `/arguslog issues` — top 10 unresolved in the default project\n"
            + "• `/arguslog issue <id>` — full detail card\n"
            + "• `/arguslog resolve <id>` — mark resolved (broadcasts to channel)\n"
            + "• `/arguslog ignore <id>` — mute a known-noisy issue (broadcasts)\n"
            + "• `/arguslog reopen <id>` — reopen a resolved/ignored issue (broadcasts)\n"
            + "• `/arguslog release <version>` — issues first seen in this release\n"
            + "• `/arguslog set-project <slug>` — switch the workspace's default project\n"
            + "• `/arguslog help` — this card";
    return List.of(section(body));
  }

  public List<Map<String, Object>> setProjectConfirmation(
      String orgSlug, String projectSlug, String projectName) {
    String url = dashboardBaseUrl + "/orgs/" + orgSlug + "/projects";
    return List.of(
        section(
            "🎯 Default project set to *<"
                + url
                + "|"
                + escape(projectName)
                + ">* (`"
                + escape(projectSlug)
                + "`). `/arguslog issues|resolve|release` will use this project from now on."));
  }

  // ── primitives ──────────────────────────────────────────────────────────

  private static Map<String, Object> section(String markdown) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "section");
    Map<String, Object> text = new LinkedHashMap<>();
    text.put("type", "mrkdwn");
    text.put("text", markdown);
    block.put("text", text);
    return block;
  }

  private static Map<String, Object> divider() {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "divider");
    return block;
  }

  private static String escape(String s) {
    // Slack mrkdwn — only & < > need protection, and only when they'd otherwise be parsed.
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  private static String stripTrailingSlash(String s) {
    if (s == null || s.isEmpty()) return "";
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
