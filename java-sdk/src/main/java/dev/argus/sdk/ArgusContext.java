package dev.argus.sdk;

import java.util.Map;

public record ArgusContext(
    Level level, Map<String, String> tags, Map<String, Object> extra, String userId) {

  public static ArgusContext empty() {
    return new ArgusContext(Level.ERROR, Map.of(), Map.of(), null);
  }

  public ArgusContext withLevel(Level level) {
    return new ArgusContext(level, tags, extra, userId);
  }

  public ArgusContext withTag(String key, String value) {
    java.util.HashMap<String, String> next = new java.util.HashMap<>(tags);
    next.put(key, value);
    return new ArgusContext(level, Map.copyOf(next), extra, userId);
  }
}
