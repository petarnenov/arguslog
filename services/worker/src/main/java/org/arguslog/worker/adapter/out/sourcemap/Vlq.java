package org.arguslog.worker.adapter.out.sourcemap;

/**
 * Base64 + VLQ codec used by sourcemap v3 mappings strings. Spec:
 * https://sourcemaps.info/spec.html#h.mofvlxcwqzej
 *
 * <p>Each VLQ value is a sign-magnitude variable-length quantity packed into base64 chars: bits
 * 0..4 are payload, bit 5 is the "more chunks follow" continuation marker. The payload's bit 0 is
 * the sign of the assembled value (1 = negative).
 */
final class Vlq {

  private static final int VLQ_BASE_SHIFT = 5;
  private static final int VLQ_BASE = 1 << VLQ_BASE_SHIFT; // 32
  private static final int VLQ_BASE_MASK = VLQ_BASE - 1; // 0x1F
  private static final int VLQ_CONTINUATION_BIT = VLQ_BASE; // 0x20

  // Standard base64 alphabet — same set sourcemap v3 uses.
  private static final String BASE64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  private static final int[] DECODE = new int[128];

  static {
    for (int i = 0; i < DECODE.length; i++) DECODE[i] = -1;
    for (int i = 0; i < BASE64.length(); i++) DECODE[BASE64.charAt(i)] = i;
  }

  private Vlq() {}

  /** Cursor over a mappings segment string. {@link #pos} advances as values are read. */
  static final class Cursor {
    final String segment;
    int pos;

    Cursor(String segment) {
      this.segment = segment;
      this.pos = 0;
    }

    boolean hasMore() {
      return pos < segment.length();
    }
  }

  /**
   * Decodes the next VLQ value from the cursor. Throws if it walks off the end mid-number — that
   * means the producer wrote a malformed mappings string and we must fail loudly so the caller can
   * fall back to the un-symbolicated frame.
   */
  static int decode(Cursor c) {
    int result = 0;
    int shift = 0;
    boolean continuation;
    do {
      if (c.pos >= c.segment.length()) {
        throw new IllegalArgumentException("truncated VLQ at " + c.pos);
      }
      char ch = c.segment.charAt(c.pos++);
      int digit = (ch < DECODE.length) ? DECODE[ch] : -1;
      if (digit < 0) throw new IllegalArgumentException("invalid base64 char '" + ch + "'");
      continuation = (digit & VLQ_CONTINUATION_BIT) != 0;
      result += (digit & VLQ_BASE_MASK) << shift;
      shift += VLQ_BASE_SHIFT;
    } while (continuation);

    boolean negative = (result & 1) == 1;
    int magnitude = result >>> 1;
    return negative ? -magnitude : magnitude;
  }
}
