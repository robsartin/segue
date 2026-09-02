package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every refusal, each seen to fire. */
class ProbeDatabaseTest {

  @TempDir Path dir;

  private static final Path HOME = Path.of("/home/invented");
  private static final Path DEFAULT = HOME.resolve(".segue").resolve("segue.db");

  @Test
  @DisplayName("should refuse when the probe database property is absent or blank")
  void shouldRefuseWhenThePropertyIsAbsentOrBlank() {
    for (String unset : new String[] {null, "", "   "}) {
      assertThatThrownBy(() -> ProbeDatabase.require(unset, null, DEFAULT, HOME))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("-Dsegue.probe.db")
          .hasMessageContaining("cp " + DEFAULT)
          .hasMessageNotContaining("SEGUE_DB");
    }
  }

  @Test
  @DisplayName("should refuse a path that is not there rather than creating a database at it")
  void shouldRefuseAPathThatIsNotThereRatherThanCreatingADatabaseAtIt() {
    // The refusal this class exists for. SqliteAssertionLog's constructor creates the file, its
    // parent directories and its schema, so a mistyped path would otherwise leave the probe
    // reading an empty database and printing five well-formed blocks of zeros.
    Path absent = dir.resolve("nothing.db");

    assertThatThrownBy(() -> ProbeDatabase.require(absent.toString(), null, DEFAULT, HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no segue database at " + absent)
        // A different mistake from the one above, and it has to read as one.
        .hasMessageNotContaining("is required");

    assertThat(Files.exists(absent)).as("the database was not created").isFalse();
  }

  @Test
  @DisplayName("should refuse the owner's own log, named directly, under the home, or symlinked")
  void shouldRefuseTheOwnersOwnLogHoweverItIsNamed() throws IOException {
    // A @TempDir posing as the home, so nothing here can reach the real one. The files below are
    // text, not databases: this class refuses a path and never opens one, and a test that had to
    // build a real log to prove that would be proving something else.
    Path home = dir.resolve("home");
    Path ownLog = Files.createDirectories(home.resolve(".segue")).resolve("segue.db");
    Files.writeString(ownLog, "the owner's log, never opened by this test");
    Path sibling = ownLog.resolveSibling("yesterday.db");
    Files.writeString(sibling, "another database in the owner's directory");
    Path symlink = dir.resolve("copy.db");
    Files.createSymbolicLink(symlink, ownLog);

    assertThatThrownBy(
            () -> ProbeDatabase.require(ownLog.toString(), null, ownLog, home),
            "the default itself")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is the owner's own log")
        .hasMessageContaining("cp " + ownLog);

    assertThatThrownBy(
            () -> ProbeDatabase.require(sibling.toString(), null, ownLog, home),
            "anything under the home's .segue, not only the default's own name")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is the owner's own log");

    assertThatThrownBy(
            () -> ProbeDatabase.require(symlink.toString(), null, ownLog, home),
            "a symlink cannot dodge it: the refusal resolves before it compares")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is the owner's own log")
        .hasMessageContaining(ownLog.toRealPath().toString());
  }

  @Test
  @DisplayName("should refuse when only SEGUE_DB names a database, and name it in the refusal")
  void shouldRefuseWhenOnlySegueDbNamesADatabase() {
    // ADR 60's clause, for ADR 60's reason: an agent's shell is initialised from the owner's
    // profile and inherits SEGUE_DB, so a variable cannot tell the owner apart from an agent
    // running as the owner. It is quoted back because it is genuinely the database that would
    // otherwise have been used, and refused in the same breath.
    assertThatThrownBy(
            () -> ProbeDatabase.require(null, "/elsewhere/segue.db", DEFAULT, HOME),
            "set, and named in the refusal")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-Dsegue.probe.db")
        .hasMessageContaining("SEGUE_DB")
        .hasMessageContaining("/elsewhere/segue.db");

    assertThatThrownBy(
            () -> ProbeDatabase.require(null, "   ", DEFAULT, HOME),
            "an exported but empty SEGUE_DB is indistinguishable from an unset one")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SEGUE_DB");
  }

  @Test
  @DisplayName("should return the copy unchanged when the property names a database that is there")
  void shouldReturnTheCopyUnchangedWhenTheNamedDatabaseIsThere() throws IOException {
    // The one green case. Without it this class is a wall rather than a fence, and every refusal
    // above would pass over an implementation that refused everything. SEGUE_DB is set here on
    // purpose: it is refused when it is all the caller gave, not when a copy was named outright.
    Path copy = dir.resolve("segue-probe.db");
    Files.writeString(copy, "a copy of the log, made by the owner");

    assertThat(ProbeDatabase.require(copy.toString(), "/elsewhere/segue.db", DEFAULT, HOME))
        .as("the copy is returned as it was named, not as it resolves")
        .isEqualTo(copy);
  }

  @Test
  @DisplayName(
      "should create nothing under the home it is handed, and never name a home of its own")
  void shouldCreateNothingUnderTheHomeItIsHanded() throws IOException {
    // The negative control, in the shape OwnCliTest and RetractCliTest use: an invented home that
    // this test never creates, and an assertion afterwards that it still does not exist. A
    // refusal that opened the path before refusing it — which is exactly what SqliteAssertionLog's
    // constructor would do — is what this catches.
    Path invented = dir.resolve("home");
    Path underIt = invented.resolve(".segue").resolve("segue.db");
    Path copy = dir.resolve("segue-probe.db");
    Files.writeString(copy, "a copy of the log, made by the owner");

    assertThat(catchThrowable(() -> ProbeDatabase.require(null, null, underIt, invented)))
        .as("absent")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            catchThrowable(
                () -> ProbeDatabase.require(null, "/elsewhere/segue.db", underIt, invented)))
        .as("SEGUE_DB only")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            catchThrowable(
                () ->
                    ProbeDatabase.require(
                        dir.resolve("absent.db").toString(), null, underIt, invented)))
        .as("a path that is not there")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            catchThrowable(
                () -> ProbeDatabase.require(underIt.toString(), null, underIt, invented)))
        .as("the owner's own log, under the invented home")
        .isInstanceOf(IllegalArgumentException.class);
    ProbeDatabase.require(copy.toString(), null, underIt, invented);

    assertThat(Files.exists(invented))
        .as("nothing was created under the home this test invented, so nothing was opened")
        .isFalse();

    // And the real ${user.home} is out of reach rather than merely unvisited: every input is a
    // parameter, so there is no run of this class that can resolve a home at all. Read off the
    // constant pool the way ADR 60's javadoc reads its fence off javap, because javadoc naming
    // System.getProperty leaves no bytecode behind and a source-text check would match its own
    // prose.
    try (InputStream bytecode = ProbeDatabase.class.getResourceAsStream("ProbeDatabase.class")) {
      assertThat(new String(bytecode.readAllBytes(), StandardCharsets.ISO_8859_1))
          .as("no environment and no system property is read inside ProbeDatabase")
          .doesNotContain("getProperty")
          .doesNotContain("getenv")
          .doesNotContain("user.home");
    }
  }
}
