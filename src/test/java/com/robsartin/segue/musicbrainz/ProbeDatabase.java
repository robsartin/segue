package com.robsartin.segue.musicbrainz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The database the probe runs against, which is a copy the owner made and never the log itself.
 *
 * <p><b>There is no default here, and there must not be one.</b> {@code SqliteAssertionLog}'s
 * constructor creates the file, its parent directories and its schema, so a probe that resolved a
 * path instead of refusing one would open the owner's own log on a typo — or create an empty
 * database and print five well-formed blocks of zeros, which is the dead-instrument failure this
 * repository has filed three times.
 *
 * <p><b>Every input is a parameter</b>, like {@code support.DefaultDatabase} and {@code
 * support.RequiredDatabase} next door: no {@code System.getProperty} and no {@code System.getenv}
 * inside this class, so no test of it can reach the owner's real home, and the class cannot acquire
 * a default by reading one. The caller passes {@code System.getProperty("segue.probe.db")}, {@code
 * System.getenv("SEGUE_DB")}, the path {@code DefaultDatabase.resolve} gives for the running user,
 * and {@code System.getProperty("user.home")}.
 *
 * <p><b>This is not {@code RequiredDatabase} reused.</b> That class belongs to the two claim CLIs,
 * is fenced to them by {@code ArchitectureTest.theClaimToolsTakeTheirDatabaseFromTheFlagAlone}, and
 * hands back a sentence for a usage message. This one lives in test source, is triggered by a
 * system property rather than a flag, and returns the path. The shape of the refusal is the same
 * because the reason is ADR 60's: an agent's shell is initialised from the owner's profile and
 * inherits {@code SEGUE_DB}, so a variable cannot tell the owner apart from an agent running as the
 * owner. It holds no copy of the env-or-home rule — the default arrives already resolved.
 */
final class ProbeDatabase {

  /** The property that names the copy. */
  static final String PROPERTY = "segue.probe.db";

  /** The directory the owner's own databases live in, under the home. */
  private static final String OWN_DIRECTORY = ".segue";

  /** A path to suggest in the refusal, so the owner's next command is a copy-paste. */
  private static final String SUGGESTED_COPY = "/tmp/segue-probe.db";

  private ProbeDatabase() {}

  /**
   * The copy named by {@code -Dsegue.probe.db}, or an {@link IllegalArgumentException} saying how
   * to make one.
   *
   * @param propertyValue {@code System.getProperty("segue.probe.db")}, or {@code null}
   * @param envSegueDb {@code System.getenv("SEGUE_DB")}, or {@code null}, passed in so this method
   *     stays pure
   * @param defaultDatabase what {@code DefaultDatabase.resolve} gives with no flag, passed in for
   *     the same reason
   * @param userHome {@code System.getProperty("user.home")} as a path, passed in for the same
   *     reason
   */
  static Path require(
      String propertyValue, String envSegueDb, Path defaultDatabase, Path userHome) {
    if (propertyValue == null || propertyValue.isBlank()) {
      throw new IllegalArgumentException(
          "-D"
              + PROPERTY
              + " is required: the probe reads a copy of the log and never the log itself. Copy it"
              + " first — cp "
              + defaultDatabase
              + " "
              + SUGGESTED_COPY
              + " — and pass -D"
              + PROPERTY
              + "="
              + SUGGESTED_COPY
              + inherited(envSegueDb));
    }
    Path named = Path.of(propertyValue);
    if (!Files.isRegularFile(named)) {
      throw new IllegalArgumentException(
          "no segue database at "
              + named
              + " — -D"
              + PROPERTY
              + " must name a copy that is already there. Nothing is created here: opening a path"
              + " that is not there writes an empty schema, and the probe then prints five"
              + " well-formed blocks of zeros");
    }
    Path resolved = resolved(named);
    if (resolved.equals(resolved(defaultDatabase))
        || resolved.startsWith(resolved(userHome.resolve(OWN_DIRECTORY)))) {
      throw new IllegalArgumentException(
          named
              + " is the owner's own log at "
              + resolved
              + ", and the probe runs against a copy. Copy it first — cp "
              + named
              + " "
              + SUGGESTED_COPY
              + " — and pass -D"
              + PROPERTY
              + "="
              + SUGGESTED_COPY);
    }
    return named;
  }

  /**
   * ADR 60's clause. {@code SEGUE_DB} is quoted back because it genuinely names a database, and
   * refused in the same breath: a shell started from the owner's profile inherits it, so it cannot
   * tell the owner apart from an agent running as the owner. Blank is treated as unset, because an
   * exported {@code SEGUE_DB=} is indistinguishable from an unset one to anyone reading a shell.
   */
  private static String inherited(String envSegueDb) {
    return envSegueDb == null || envSegueDb.isBlank()
        ? ""
        : ". SEGUE_DB is set ("
            + envSegueDb
            + ") and does not stand in for the property: a shell started from the owner's profile"
            + " inherits it, so it cannot tell the owner apart from an agent running as the owner"
            + " (ADR 60)";
  }

  /**
   * The path with every symlink resolved, so a link to the owner's log is the owner's log, or —
   * when there is nothing there to resolve — what the path names. The fallback is reachable only
   * for {@code defaultDatabase} and the home, both of which may be absent on a machine that has
   * never run a claim tool; the named path itself was refused above if it was not there.
   */
  private static Path resolved(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }
}
