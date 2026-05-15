package org.arguslog.api.slack.adapter.out.postgres;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.security.OrgContext;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.arguslog.crypto.SecretCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * {@code @ConditionalOnProperty(arguslog.slack.enabled, matchIfMissing = true)} loads this in
 * production by default, but lets test contexts opt out by setting the property to false. The
 * same guard sits on {@link org.arguslog.api.slack.application.SlackCommandDispatcher} and
 * {@link org.arguslog.api.slack.adapter.in.web.SlackController}, so the three skip together —
 * no half-loaded graph that wedges Spring's bean factory.
 */
@Component
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcSlackWorkspaceRepository
    implements SlackWorkspaceRepository, SlackWorkspaceWriteRepository {

  private final JdbcTemplate jdbc;
  private final SecretCipher cipher;
  private final RowMapper<SlackWorkspace> rowMapper = this::mapRow;

  public JdbcSlackWorkspaceRepository(DataSource dataSource, SecretCipher cipher) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.cipher = cipher;
  }

  @Override
  public Optional<SlackWorkspace> findActiveByTeamId(String slackTeamId) {
    // No RLS pin — the Slack dispatcher discovers the org from the result of this query, so
    // pinning here would be circular. Safety is maintained because slack_team_id is unique
    // and the caller scopes every subsequent action to the row's org_id.
    try {
      SlackWorkspace row =
          jdbc.queryForObject(
              """
              SELECT id, slack_team_id, slack_team_name, install_token_encrypted, org_id,
                     default_project_id, installed_by_user_id, installed_at, deactivated_at,
                     webhook_url_encrypted, webhook_channel
                FROM slack_workspaces
               WHERE slack_team_id = ?
                 AND deactivated_at IS NULL
              """,
              rowMapper,
              slackTeamId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<SlackWorkspace> listForOrg(long orgId) {
    pinOrgContextForRls();
    return jdbc.query(
        """
        SELECT id, slack_team_id, slack_team_name, install_token_encrypted, org_id,
               default_project_id, installed_by_user_id, installed_at, deactivated_at,
               webhook_url_encrypted, webhook_channel
          FROM slack_workspaces
         WHERE org_id = ?
         ORDER BY installed_at DESC, id DESC
        """,
        rowMapper,
        orgId);
  }

  @Override
  public SlackWorkspace upsert(
      String slackTeamId,
      String slackTeamName,
      String installToken,
      long orgId,
      Long defaultProjectId,
      UUID installedByUserId,
      String webhookUrl,
      String webhookChannel) {
    String encrypted = encryptToBase64(installToken);
    String webhookEncrypted = webhookUrl == null ? null : encryptToBase64(webhookUrl);
    return jdbc.queryForObject(
        """
        INSERT INTO slack_workspaces (slack_team_id, slack_team_name, install_token_encrypted,
                                      org_id, default_project_id, installed_by_user_id,
                                      webhook_url_encrypted, webhook_channel)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (slack_team_id)
            DO UPDATE SET slack_team_name         = EXCLUDED.slack_team_name,
                          install_token_encrypted = EXCLUDED.install_token_encrypted,
                          org_id                  = EXCLUDED.org_id,
                          default_project_id      = EXCLUDED.default_project_id,
                          installed_by_user_id    = EXCLUDED.installed_by_user_id,
                          webhook_url_encrypted   = EXCLUDED.webhook_url_encrypted,
                          webhook_channel         = EXCLUDED.webhook_channel,
                          installed_at            = NOW(),
                          deactivated_at          = NULL
          RETURNING id, slack_team_id, slack_team_name, install_token_encrypted, org_id,
                    default_project_id, installed_by_user_id, installed_at, deactivated_at,
                    webhook_url_encrypted, webhook_channel
        """,
        rowMapper,
        slackTeamId,
        slackTeamName,
        encrypted,
        orgId,
        defaultProjectId,
        installedByUserId,
        webhookEncrypted,
        webhookChannel);
  }

  private String encryptToBase64(String plaintext) {
    return Base64.getEncoder().encodeToString(cipher.encrypt(plaintext.getBytes(StandardCharsets.UTF_8)));
  }

  @Override
  public void deactivate(long workspaceId) {
    // Pin OrgContext for RLS so a caller in org A cannot UPDATE a row in org B by guessing an
    // id. SlackInstallController's callback path doesn't hit this (it only upserts), so the
    // OrgContext requirement is fine here — every legitimate caller (dashboard DELETE,
    // future slash-command uninstall) runs inside an OrgAccessGuard-pinned context.
    pinOrgContextForRls();
    jdbc.update(
        "UPDATE slack_workspaces SET deactivated_at = NOW() WHERE id = ? AND deactivated_at IS NULL",
        workspaceId);
  }

  @Override
  public SlackWorkspace setDefaultProject(long workspaceId, Long defaultProjectId) {
    // Same RLS-pinning rationale as deactivate(). Returns null if RLS filters the row out;
    // callers that need a "found" guarantee should listForOrg + verify before calling.
    pinOrgContextForRls();
    return jdbc.queryForObject(
        """
        UPDATE slack_workspaces
           SET default_project_id = ?
         WHERE id = ?
        RETURNING id, slack_team_id, slack_team_name, install_token_encrypted, org_id,
                  default_project_id, installed_by_user_id, installed_at, deactivated_at,
                  webhook_url_encrypted, webhook_channel
        """,
        rowMapper,
        defaultProjectId,
        workspaceId);
  }

  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private SlackWorkspace mapRow(ResultSet rs, int rowNum) throws SQLException {
    byte[] encrypted = Base64.getDecoder().decode(rs.getString("install_token_encrypted"));
    String token = new String(cipher.decrypt(encrypted), StandardCharsets.UTF_8);
    Object installedBy = rs.getObject("installed_by_user_id");
    UUID installedByUuid = installedBy instanceof UUID u ? u : null;
    long defaultProjectRaw = rs.getLong("default_project_id");
    Long defaultProject = rs.wasNull() ? null : defaultProjectRaw;
    java.sql.Timestamp deactivatedAt = rs.getTimestamp("deactivated_at");
    String webhookEncrypted = rs.getString("webhook_url_encrypted");
    String webhookUrl = null;
    if (webhookEncrypted != null) {
      webhookUrl =
          new String(
              cipher.decrypt(Base64.getDecoder().decode(webhookEncrypted)), StandardCharsets.UTF_8);
    }
    return new SlackWorkspace(
        rs.getLong("id"),
        rs.getString("slack_team_id"),
        rs.getString("slack_team_name"),
        token,
        rs.getLong("org_id"),
        defaultProject,
        installedByUuid,
        rs.getTimestamp("installed_at").toInstant(),
        deactivatedAt == null ? null : deactivatedAt.toInstant(),
        webhookUrl,
        rs.getString("webhook_channel"));
  }
}
