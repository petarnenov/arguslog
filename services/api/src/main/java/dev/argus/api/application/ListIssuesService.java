package dev.argus.api.application;

import dev.argus.api.application.port.IssueRepository;
import dev.argus.api.application.port.IssueRepository.Cursor;
import dev.argus.api.domain.Issue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListIssuesService implements ListIssuesUseCase {

  private final IssueRepository repository;

  public ListIssuesService(IssueRepository repository) {
    this.repository = repository;
  }

  // Read-only TX so the SET LOCAL inside JdbcIssueRepository.pinOrgContextForRls scopes to this
  // request, and PG can route to a hot-standby when we add one in P5.
  @Override
  @Transactional(readOnly = true)
  public Page list(Query query) {
    Optional<Cursor> cursor = query.cursor().map(ListIssuesService::decode);

    // N+1 trick: ask for one more than requested; if we got it, there is a next page.
    List<Issue> rows =
        repository.page(
            query.projectId(), query.status(), query.level(), cursor, query.limit() + 1);

    boolean hasMore = rows.size() > query.limit();
    List<Issue> page = hasMore ? rows.subList(0, query.limit()) : rows;
    Optional<String> next =
        hasMore
            ? Optional.of(
                encode(
                    new Cursor(
                        page.get(page.size() - 1).lastSeenAt(), page.get(page.size() - 1).id())))
            : Optional.empty();

    return new Page(page, next);
  }

  static String encode(Cursor cursor) {
    String raw = cursor.lastSeenAt().toString() + "|" + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static Cursor decode(String token) {
    String raw;
    try {
      raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new InvalidCursorException("cursor is not valid base64", e);
    }
    int sep = raw.indexOf('|');
    if (sep <= 0 || sep == raw.length() - 1) {
      throw new InvalidCursorException("cursor missing separator");
    }
    try {
      return new Cursor(
          Instant.parse(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
    } catch (RuntimeException e) {
      throw new InvalidCursorException("cursor fields unparseable", e);
    }
  }

  public static final class InvalidCursorException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidCursorException(String message) {
      super(message);
    }

    public InvalidCursorException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
