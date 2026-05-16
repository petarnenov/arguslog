package org.arguslog.api.alerts.domain;

public enum DestinationKind {
  TELEGRAM,
  EMAIL,
  SLACK,
  WEBHOOK,
  GITHUB_ISSUE;

  public String dbValue() {
    return name().toLowerCase();
  }

  public static DestinationKind fromString(String value) {
    if (value == null) throw new IllegalArgumentException("destination kind required");
    for (DestinationKind k : values()) {
      if (k.dbValue().equalsIgnoreCase(value)) {
        return k;
      }
    }
    throw new IllegalArgumentException("unknown destination kind: " + value);
  }
}
