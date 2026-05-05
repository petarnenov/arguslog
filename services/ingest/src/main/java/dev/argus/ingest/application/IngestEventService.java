package dev.argus.ingest.application;

import dev.argus.ingest.application.port.EventStreamPublisher;
import dev.argus.ingest.application.port.ProjectAuthenticator;
import dev.argus.ingest.application.port.QuotaEnforcer;
import dev.argus.ingest.domain.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IngestEventService implements IngestEventUseCase {

  private static final int MAX_PAYLOAD_BYTES = 200 * 1024; // 200 KB

  private final ProjectAuthenticator authenticator;
  private final QuotaEnforcer quotaEnforcer;
  private final EventStreamPublisher publisher;
  private final Clock clock;

  public IngestEventService(
      ProjectAuthenticator authenticator,
      QuotaEnforcer quotaEnforcer,
      EventStreamPublisher publisher,
      Clock clock) {
    this.authenticator = authenticator;
    this.quotaEnforcer = quotaEnforcer;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  public Result ingest(Command command) {
    if (command.rawPayload().length() > MAX_PAYLOAD_BYTES) {
      return new Result.PayloadTooLarge();
    }
    if (authenticator.authenticate(command.projectId(), command.dsnPublicKey()).isEmpty()) {
      return new Result.Unauthorized();
    }
    QuotaEnforcer.Decision decision = quotaEnforcer.tryConsume(command.projectId());
    return switch (decision) {
      case RATE_LIMITED -> new Result.RateLimited();
      case QUOTA_EXCEEDED -> new Result.QuotaExceeded();
      case ALLOW -> publishAccepted(command);
    };
  }

  private Result.Accepted publishAccepted(Command command) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            command.projectId(),
            command.dsnPublicKey(),
            Instant.now(clock),
            command.rawPayload(),
            command.clientIp(),
            command.userAgent());
    publisher.publish(envelope);
    return new Result.Accepted(envelope);
  }
}
