package dev.argus.api.security;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AccessExceptionHandler {

  @ExceptionHandler(AccessException.class)
  public ResponseEntity<ProblemDetail> handle(AccessException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
    body.setTitle(e.status() == 404 ? "Not found" : "Forbidden");
    body.setType(
        URI.create(
            "https://argus.dev/problems/" + (e.status() == 404 ? "not-found" : "forbidden")));
    return ResponseEntity.status(e.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
