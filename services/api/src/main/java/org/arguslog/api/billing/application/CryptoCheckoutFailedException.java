package org.arguslog.api.billing.application;

public class CryptoCheckoutFailedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public CryptoCheckoutFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
