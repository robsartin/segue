package com.robsartin.segue.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The context loads. Trivial, and the first thing to break when wiring goes wrong. */
@SpringBootTest
class SegueApplicationTest {

  @Test
  void contextLoads() {
    // Deliberately empty: the assertion is that @SpringBootTest got this far.
  }
}
