package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the sentence the two claim tools refuse with — one copy, for the reason {@code
 * DefaultDatabase} exists.
 *
 * <p>The first cut of #179 gave {@code RetractCli} and {@code OwnCli} a private copy each of the
 * env-or-home rule, purely to name the path the refusal quotes back. That was a third and fourth
 * copy of the rule this issue exists to delete, and it slipped past the design because the fence
 * names {@code DefaultDatabase} literally: re-implementing the rule never touches the forbidden
 * class. This class holds the rule once, by calling {@link DefaultDatabase#resolve} itself.
 *
 * <p><b>It hands back a sentence and never a {@link Path}.</b> That is what keeps the boundary
 * meaningful rather than merely literal - the two tools cannot use it as a database even by
 * mistake, because there is no path to take from it.
 */
class RequiredDatabaseTest {

  @Test
  @DisplayName("should name the SEGUE_DB database when the variable is set")
  void shouldNameTheEnvironmentDatabaseWhenTheVariableIsSet() {
    String refusal = RequiredDatabase.refusal("/from/env/segue.db", "/home/rob");

    assertThat(refusal).contains("--db").contains("/from/env/segue.db");
  }

  @Test
  @DisplayName("should name the home database when SEGUE_DB is not set")
  void shouldNameTheHomeDatabaseWhenTheVariableIsNotSet() {
    String refusal = RequiredDatabase.refusal(null, "/home/rob");

    assertThat(refusal)
        .contains("--db")
        .contains(Path.of("/home/rob", ".segue", "segue.db").toString());
  }

  @Test
  @DisplayName("should say why SEGUE_DB is not enough on its own")
  void shouldSayWhyTheVariableIsNotEnoughOnItsOwn() {
    // An owner who has SEGUE_DB set will otherwise reasonably conclude the tool is broken. The
    // answer - a shell started from their profile inherits it, so it cannot tell them apart from
    // an agent running as them - is the whole of why this refusal exists.
    assertThat(RequiredDatabase.refusal("/from/env/segue.db", "/home/rob"))
        .contains("SEGUE_DB")
        .contains("typed per invocation");
  }
}
