package org.arguslog.sdk;

import java.util.Objects;

/** Static-facing facade around the configured {@link ArgusClient}. */
public final class Arguslog {

  private static volatile ArgusClient client;

  private Arguslog() {}

  public static synchronized void init(ArgusOptions options) {
    Objects.requireNonNull(options, "options");
    if (client != null) {
      client.close();
    }
    client = new ArgusClient(options);
  }

  public static ArgusClient getClient() {
    return client;
  }

  public static String captureException(Throwable error) {
    return client == null ? null : client.captureException(error, null);
  }

  public static String captureException(Throwable error, ArgusContext context) {
    return client == null ? null : client.captureException(error, context);
  }

  public static String captureMessage(String message, Level level) {
    return client == null ? null : client.captureMessage(message, level);
  }

  public static void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }
}
