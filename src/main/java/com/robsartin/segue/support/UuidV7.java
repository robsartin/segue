package com.robsartin.segue.support;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Version 7 UUIDs, per RFC 9562.
 *
 * <p>The JDK has no v7 generator — {@code UUID.randomUUID()} is version 4, which is unordered.
 * Version 7 puts a millisecond timestamp in the leading 48 bits, so identifiers sort by the time
 * they were minted and a log tail reads chronologically without anyone parsing dates.
 *
 * <p>Hand-written rather than pulling a dependency: the layout is fully specified and about fifteen
 * lines, and it is asserted against the RFC in {@code UuidV7Test}. A library becomes the right
 * answer only if guaranteed monotonicity *within* a millisecond is ever needed, which a correlation
 * identifier does not require.
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     unix_ts_ms (48 bits)                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ver (0111)   |       rand_a (12 bits)        | var(10)|      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     rand_b (62 bits)                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  /** A fresh version-7 identifier. */
  public static UUID generate() {
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);

    long millis = System.currentTimeMillis();

    long most = millis << 16;
    most |= (long) (random[0] & 0x0F) << 8;
    most |= random[1] & 0xFF;
    most &= ~(0xFL << 12);
    most |= 0x7L << 12; // version 7

    long least = 0;
    for (int i = 2; i < 10; i++) {
      least = (least << 8) | (random[i] & 0xFF);
    }
    least &= ~(0x3L << 62);
    least |= 0x2L << 62; // RFC 4122 variant

    return new UUID(most, least);
  }
}
