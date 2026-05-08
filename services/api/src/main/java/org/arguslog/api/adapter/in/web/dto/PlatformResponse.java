package org.arguslog.api.adapter.in.web.dto;

import org.arguslog.api.domain.Platform;

public record PlatformResponse(String slug, String name, String sdkPackage, String sdkVersion) {

  public static PlatformResponse from(Platform p) {
    return new PlatformResponse(p.slug(), p.name(), p.sdkPackage(), p.sdkVersion());
  }
}
