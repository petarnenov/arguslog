package org.arguslog.api.adapter.in.web;

import java.util.List;
import org.arguslog.api.adapter.in.web.dto.PlatformResponse;
import org.arguslog.api.application.PlatformUseCase;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public catalog of supported SDK platforms. Drives the project-create dropdown in the dashboard
 * and is also intended to back the future MCP {@code list_platforms} tool — both consumers want the
 * same shape, so the endpoint is permitAll (the list is non-sensitive and identical to what we'd
 * put on a marketing page).
 */
@RestController
@RequestMapping(value = "/api/v1/platforms", produces = MediaType.APPLICATION_JSON_VALUE)
public class PlatformController {

  private final PlatformUseCase useCase;

  public PlatformController(PlatformUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<PlatformResponse> list() {
    return useCase.list().stream().map(PlatformResponse::from).toList();
  }
}
