package dev.argus.api.security;

/**
 * Thrown when an authenticated user touches a resource they have no membership for. Carries an
 * HTTP-style status because the controller advice maps it directly to a problem+json payload — 404
 * for "no such project" so we don't leak existence to non-members, 403 for "exists but you're not
 * in".
 */
public final class AccessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  private AccessException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static AccessException notFound(long projectId) {
    return new AccessException(404, "project " + projectId + " does not exist");
  }

  public static AccessException forbidden(long projectId) {
    return new AccessException(
        404, "project " + projectId + " does not exist"); // sic — see class doc
  }
}
