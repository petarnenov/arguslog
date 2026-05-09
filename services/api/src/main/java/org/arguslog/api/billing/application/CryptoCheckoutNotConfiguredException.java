package org.arguslog.api.billing.application;

public class CryptoCheckoutNotConfiguredException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public CryptoCheckoutNotConfiguredException(String message) {
    super(message);
  }
}
