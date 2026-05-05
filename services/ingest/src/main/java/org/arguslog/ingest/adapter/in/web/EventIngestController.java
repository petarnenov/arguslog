package org.arguslog.ingest.adapter.in.web;

import org.arguslog.ingest.application.IngestEventUseCase;
import org.arguslog.ingest.application.IngestEventUseCase.Command;
import org.arguslog.ingest.application.IngestEventUseCase.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/{projectId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
public class EventIngestController {

  private static final String AUTH_HEADER = "X-Argus-Auth";
  private static final String AUTH_PREFIX = "Argus DSN ";

  private final IngestEventUseCase ingest;

  public EventIngestController(IngestEventUseCase ingest) {
    this.ingest = ingest;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> ingest(
      @PathVariable long projectId,
      @RequestHeader(value = AUTH_HEADER, required = false) String authHeader,
      @RequestBody String rawPayload,
      HttpServletRequest request) {

    String dsn = parseDsn(authHeader);
    if (dsn == null) {
      return ResponseEntity.status(401).body(Map.of("error", "missing_or_malformed_dsn"));
    }

    Result result =
        ingest.ingest(
            new Command(projectId, dsn, rawPayload, clientIp(request), userAgent(request)));

    return switch (result) {
      case Result.Accepted accepted ->
          ResponseEntity.accepted()
              .body(Map.of("eventId", accepted.envelope().eventId().toString()));
      case Result.Unauthorized __ ->
          ResponseEntity.status(401).body(Map.of("error", "invalid_dsn"));
      case Result.RateLimited __ ->
          ResponseEntity.status(429)
              .header(HttpHeaders.RETRY_AFTER, "1")
              .body(Map.of("error", "rate_limited"));
      case Result.QuotaExceeded __ ->
          ResponseEntity.status(429).body(Map.of("error", "quota_exceeded"));
      case Result.PayloadTooLarge __ ->
          ResponseEntity.status(413).body(Map.of("error", "payload_too_large"));
    };
  }

  private static String parseDsn(String header) {
    if (header == null || !header.startsWith(AUTH_PREFIX)) {
      return null;
    }
    String value = header.substring(AUTH_PREFIX.length()).trim();
    return value.isEmpty() ? null : value;
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }

  private static String userAgent(HttpServletRequest request) {
    return request.getHeader("User-Agent");
  }
}
