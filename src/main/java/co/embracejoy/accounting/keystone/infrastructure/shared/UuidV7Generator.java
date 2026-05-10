package co.embracejoy.accounting.keystone.infrastructure.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates UUID v7 identifiers per RFC 9562: 128-bit time-ordered, sortable.
 *
 * <p>Layout (most-significant bit first):
 *
 * <pre>
 *   | 48 bits unix-millis timestamp | 4 bits version (0x7) | 12 bits random_a |
 *   | 2 bits variant (0b10) | 62 bits random_b |
 * </pre>
 *
 * <p>JDK 25's {@link UUID} class does not provide a v7 factory; when one lands upstream this class
 * becomes a thin wrapper.
 */
public final class UuidV7Generator {

  private static final SecureRandom RNG = new SecureRandom();

  private UuidV7Generator() {
    // utility class
  }

  public static UUID create() {
    long ts = Instant.now().toEpochMilli();
    long randA = RNG.nextInt(0x1000); // 12 bits
    long randB = RNG.nextLong() & 0x3FFFFFFFFFFFFFFFL; // 62 bits

    long msb = (ts << 16) | (0x7L << 12) | randA;
    long lsb = (1L << 63) | randB;

    return new UUID(msb, lsb);
  }
}
