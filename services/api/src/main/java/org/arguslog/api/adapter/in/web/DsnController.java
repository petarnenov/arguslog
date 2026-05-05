package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import org.arguslog.api.adapter.in.web.dto.DsnResponse;
import org.arguslog.api.application.DsnUseCase;
import org.arguslog.api.domain.Dsn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/v1/projects/{projectId}/keys",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class DsnController {

  private final DsnUseCase useCase;
  private final String ingestHost;

  public DsnController(
      DsnUseCase useCase,
      @Value("${argus.ingest.public-host:http://localhost:8080}") String ingestHost) {
    this.useCase = useCase;
    this.ingestHost = ingestHost;
  }

  @GetMapping
  public List<DsnResponse> list(@PathVariable long projectId) {
    return useCase.list(projectId).stream().map(d -> DsnResponse.from(d, ingestHost)).toList();
  }

  @PostMapping
  public ResponseEntity<DsnResponse> create(@PathVariable long projectId) {
    Dsn created = useCase.create(projectId);
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(DsnResponse.from(created, ingestHost));
  }
}
