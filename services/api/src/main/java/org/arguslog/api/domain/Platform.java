package org.arguslog.api.domain;

public record Platform(
    String slug, String name, String sdkPackage, String sdkVersion, int sortOrder) {}
