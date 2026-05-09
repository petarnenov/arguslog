package org.arguslog.api.application;

public class ProjectCapExceededException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ProjectCapExceededException(String message) {
    super(message);
  }
}
