package org.arguslog.api.auth.adapter.out.crypto;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.springframework.stereotype.Component;

/**
 * argon2id with conservative defaults — opaque PATs are 48 chars of high-entropy random so we don't
 * need the heavy parameters typically used for human passwords. Cost stays low enough to keep auth
 * latency under ~50 ms on commodity hardware while still being slow enough that a leaked database
 * dump is not trivially crackable.
 */
@Component
public class Argon2TokenHasher implements TokenHasher {

  private static final int ITERATIONS = 2;
  private static final int MEMORY_KIB = 32_768; // 32 MiB
  private static final int PARALLELISM = 1;

  private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);

  @Override
  public String hash(String plaintext) {
    char[] chars = plaintext.toCharArray();
    try {
      return argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  @Override
  public boolean matches(String plaintext, String storedHash) {
    char[] chars = plaintext.toCharArray();
    try {
      return argon2.verify(storedHash, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }
}
