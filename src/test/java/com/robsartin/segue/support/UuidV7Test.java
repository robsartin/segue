package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RFC 9562 version 7. The JDK has no generator — {@code UUID.randomUUID()} is version 4 — and
 * version 4 is unordered, so logs cannot be sorted by identifier.
 */
class UuidV7Test {

  @Test
  @DisplayName("it is version 7 and RFC 4122 variant")
  void hasCorrectVersionAndVariant() {
    UUID id = UuidV7.generate();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  @DisplayName("the leading 48 bits are the current Unix time in milliseconds")
  void encodesTimestamp() {
    long before = System.currentTimeMillis();
    UUID id = UuidV7.generate();
    long after = System.currentTimeMillis();

    long timestamp = id.getMostSignificantBits() >>> 16;

    assertThat(timestamp).isBetween(before - 1000, after + 1000);
    assertThat(Instant.ofEpochMilli(timestamp)).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("identifiers minted in sequence sort in the order they were minted")
  void sortsChronologically() {
    // This is the whole reason for v7 over v4: a log tail reads chronologically without
    // parsing timestamps.
    List<UUID> minted =
        IntStream.range(0, 200)
            .mapToObj(
                i -> {
                  if (i % 50 == 0) {
                    try {
                      Thread.sleep(2);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                  return UuidV7.generate();
                })
            .toList();

    List<UUID> sorted =
        minted.stream()
            .sorted(java.util.Comparator.comparingLong(u -> u.getMostSignificantBits() >>> 16))
            .toList();

    assertThat(sorted).isEqualTo(minted);
  }

  @Test
  @DisplayName("two identifiers minted in the same millisecond still differ")
  void isUnique() {
    assertThat(IntStream.range(0, 10_000).mapToObj(i -> UuidV7.generate()).distinct().count())
        .isEqualTo(10_000);
  }
}
