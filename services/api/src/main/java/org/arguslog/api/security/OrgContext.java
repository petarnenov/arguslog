package org.arguslog.api.security;

import java.util.Optional;

/**
 * ThreadLocal holder for the org_id resolved by {@link ProjectAccessGuard} for the current request.
 * Read by the persistence layer immediately before each query, so the {@code SET LOCAL
 * arguslog.org_id} value lines up with the request's authorized scope. Cleared in the interceptor's
 * afterCompletion to avoid leaking into the next request on a reused worker thread.
 */
public final class OrgContext {

  private static final ThreadLocal<Long> ORG_ID = new ThreadLocal<>();

  private OrgContext() {}

  public static void set(long orgId) {
    ORG_ID.set(orgId);
  }

  public static Optional<Long> current() {
    return Optional.ofNullable(ORG_ID.get());
  }

  public static long requireCurrent() {
    Long id = ORG_ID.get();
    if (id == null) {
      throw new IllegalStateException(
          "OrgContext is empty — RLS-sensitive query attempted outside a guarded request");
    }
    return id;
  }

  public static void clear() {
    ORG_ID.remove();
  }
}
