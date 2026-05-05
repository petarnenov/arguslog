package dev.argus.sdk;

import java.net.URI;
import java.util.Objects;

public record Dsn(
    String publicKey, String host, String scheme, String projectId, String ingestUrl) {

  public static Dsn parse(String raw) {
    Objects.requireNonNull(raw, "dsn");
    URI uri;
    try {
      uri = URI.create(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid DSN: " + raw, e);
    }
    String userInfo = uri.getUserInfo();
    String host = uri.getHost();
    String scheme = uri.getScheme();
    String path = uri.getPath();
    if (userInfo == null
        || host == null
        || scheme == null
        || path == null
        || path.length() <= 1
        || (!"http".equals(scheme) && !"https".equals(scheme))) {
      throw new IllegalArgumentException("Invalid DSN: " + raw);
    }
    String projectId = path.substring(1);
    int port = uri.getPort();
    String authority = port == -1 ? host : host + ":" + port;
    String ingestUrl = scheme + "://" + authority + "/api/" + projectId + "/events";
    return new Dsn(userInfo, host, scheme, projectId, ingestUrl);
  }
}
