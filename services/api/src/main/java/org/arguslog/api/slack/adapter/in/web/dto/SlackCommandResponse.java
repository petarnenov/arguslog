package org.arguslog.api.slack.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Slack slash-command response envelope. {@code response_type=ephemeral} means only the user who
 * ran the command sees the reply (no channel noise); {@code in_channel} broadcasts. The dispatcher
 * picks ephemeral for read-only commands and in_channel for mutating ones (resolve, ping) so the
 * team has an audit trail.
 *
 * <p>Block Kit blocks are passed through as untyped {@code Map<String,Object>} — the structure is
 * large, well-documented at https://api.slack.com/reference/block-kit/blocks, and producing a typed
 * hierarchy would just add weight for no safety benefit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlackCommandResponse(
    String response_type, String text, List<Map<String, Object>> blocks) {

  public static SlackCommandResponse ephemeral(String text, List<Map<String, Object>> blocks) {
    return new SlackCommandResponse("ephemeral", text, blocks);
  }

  public static SlackCommandResponse inChannel(String text, List<Map<String, Object>> blocks) {
    return new SlackCommandResponse("in_channel", text, blocks);
  }

  public static SlackCommandResponse ephemeralText(String text) {
    return new SlackCommandResponse("ephemeral", text, null);
  }
}
