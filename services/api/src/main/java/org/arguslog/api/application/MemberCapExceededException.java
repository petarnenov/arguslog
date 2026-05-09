package org.arguslog.api.application;

public class MemberCapExceededException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MemberCapExceededException(String message) {
    super(message);
  }
}
