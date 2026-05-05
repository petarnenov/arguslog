package dev.argus.sdk;

public enum Level {
  FATAL,
  ERROR,
  WARNING,
  INFO,
  DEBUG;

  public String toWire() {
    return name().toLowerCase();
  }
}
