package org.arguslog.api.admin.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Allowlist of email addresses that are platform administrators. Sourced from {@code
 * arguslog.platform-admins} (env: {@code ARGUSLOG_PLATFORM_ADMINS=email1,email2}). Empty list →
 * NO admins, every {@code /api/v1/admin/**} request returns 403. Lowercased on read so case
 * variations from the JWT email claim never matter.
 */
@ConfigurationProperties(prefix = "arguslog")
public record PlatformAdminProperties(List<String> platformAdmins) {

  public PlatformAdminProperties {
    if (platformAdmins == null) platformAdmins = List.of();
  }

  public Set<String> normalizedEmails() {
    Set<String> out = new HashSet<>(platformAdmins.size());
    for (String e : platformAdmins) {
      if (e == null) continue;
      String trimmed = e.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
  }
}
