package org.arguslog.sdk;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Dsn(
    String publicKey, String host, String scheme, String projectId, String ingestUrl) {

  // User-facing DSN format (matches what the api emits in the project-create response and shows
  // in the web UI's "Copy DSN" modal): arguslog://<publicKey>@<host>/api/<projectId>
  // Kept in lockstep with packages/sdk-browser/src/dsn.ts.
  private static final Pattern DSN_RE =
      Pattern.compile("^arguslog://([^@]+)@([^/]+)/api/([^/?#]+)$");

  public static Dsn parse(String raw) {
    Objects.requireNonNull(raw, "dsn");
    Matcher m = DSN_RE.matcher(raw);
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid DSN: " + raw);
    }
    String publicKey = m.group(1);
    String host = m.group(2);
    String projectId = m.group(3);
    if (publicKey.isEmpty() || host.isEmpty() || projectId.isEmpty()) {
      throw new IllegalArgumentException("Invalid DSN: " + raw);
    }
    String scheme = isLoopback(host) ? "http" : "https";
    String ingestUrl = scheme + "://" + host + "/api/" + projectId + "/events";
    return new Dsn(publicKey, host, scheme, projectId, ingestUrl);
  }

  private static boolean isLoopback(String host) {
    String bare =
        host.startsWith("[")
            ? host.substring(0, host.indexOf(']') + 1)
            : host.split(":", 2)[0];
    return bare.equals("localhost")
        || bare.startsWith("127.")
        || bare.equals("0.0.0.0")
        || bare.equals("[::1]")
        || bare.equals("::1");
  }
}
