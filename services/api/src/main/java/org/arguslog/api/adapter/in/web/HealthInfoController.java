package org.arguslog.api.adapter.in.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthInfoController {

  @GetMapping("/api/v1/info")
  public Map<String, String> info() {
    return Map.of("name", "arguslog-api", "version", "0.0.1-SNAPSHOT");
  }
}
