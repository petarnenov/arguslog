package org.arguslog.api.slack.adapter.in.web.dto;

/**
 * Subset of the form-encoded fields Slack POSTs to the slash-command endpoint. The full
 * payload has ~20 fields; we only care about these. See
 * https://docs.slack.dev/interactivity/implementing-slash-commands.
 *
 * <p>{@code text} is everything after the slash-command name — the dispatcher splits it on
 * whitespace to get subcommand + args.
 */
public record SlackCommandPayload(
    String teamId,
    String teamDomain,
    String channelId,
    String userId,
    String command,
    String text,
    String responseUrl) {}
