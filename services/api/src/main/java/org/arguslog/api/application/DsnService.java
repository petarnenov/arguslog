package org.arguslog.api.application;

import java.security.SecureRandom;
import java.util.List;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.domain.Dsn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DsnService implements DsnUseCase {

  /**
   * 20 random bytes → 32 base32 chars. Long enough that brute-forcing a valid public key would take
   * implausibly many tries even at unbounded ingest QPS, short enough to paste comfortably into an
   * env var. Public-key-only — no secret half is generated (P1 contract).
   */
  private static final int KEY_BYTES = 20;

  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private final DsnRepository dsns;
  private final SecureRandom random = new SecureRandom();

  public DsnService(DsnRepository dsns) {
    this.dsns = dsns;
  }

  @Override
  @Transactional
  public Dsn create(long projectId) {
    return dsns.create(projectId, generatePublicKey());
  }

  @Override
  @Transactional(readOnly = true)
  public List<Dsn> list(long projectId) {
    return dsns.listForProject(projectId);
  }

  private String generatePublicKey() {
    byte[] bytes = new byte[KEY_BYTES];
    random.nextBytes(bytes);
    return base32(bytes);
  }

  private static String base32(byte[] in) {
    StringBuilder out = new StringBuilder((in.length * 8 + 4) / 5);
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : in) {
      buffer = (buffer << 8) | (b & 0xff);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        out.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1f]);
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      out.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1f]);
    }
    return out.toString();
  }
}
