package com.robsartin.segue.support;

import java.nio.file.Path;

/**
 * The refusal the two claim tools give when {@code --db} was not typed (#179).
 *
 * <p><b>A sentence, not a path.</b> {@code RetractCli} and {@code OwnCli} have no default database
 * and must not acquire one, so this class hands back a {@link String} and never a {@link Path}:
 * there is nothing here either tool could use as a database even by mistake, which is what keeps
 * that boundary meaningful rather than merely literal.
 *
 * <p><b>Why it exists at all.</b> The refusal has to name the database the tool would once have
 * defaulted to, or the owner's next command is a lookup rather than a copy-paste - and naming it
 * means resolving it. The first cut of #179 did that with a private copy of the env-or-home rule in
 * each tool, which was a third and fourth copy of the one rule {@link DefaultDatabase} exists to
 * hold. The copies slipped past the design because the fence names {@code DefaultDatabase}
 * literally, and re-implementing a rule never touches the class that owns it. So the resolution
 * happens here, once, by calling {@link DefaultDatabase#resolve} - and the two tools depend on this
 * class instead, not on {@code DefaultDatabase}, which leaves the intended ArchUnit fence exactly
 * as strong as it was.
 *
 * <p><b>Pure</b>, like its neighbour: no {@code System.getenv} and no {@code System.getProperty}
 * inside it. The caller passes both in.
 */
public final class RequiredDatabase {

  private RequiredDatabase() {}

  /**
   * "You have to say which database", plus the one it would have used, plus why the environment
   * variable is not enough.
   *
   * <p>The path is in the sentence so the owner's next command is a copy-paste. {@code SEGUE_DB} is
   * quoted back when it is set - it is genuinely the database the tool would have used - and is
   * still refused, because an agent's shell is initialised from the owner's profile and inherits
   * it. A variable cannot tell the owner apart from an agent running as the owner; a flag typed per
   * invocation can.
   *
   * @param segueDbEnvOrNull {@code System.getenv("SEGUE_DB")}, passed in by the caller so this
   *     method stays pure
   * @param userHome {@code System.getProperty("user.home")}, passed in for the same reason
   */
  public static String refusal(String segueDbEnvOrNull, String userHome) {
    return "--db is required — pass --db "
        + DefaultDatabase.resolve(null, segueDbEnvOrNull, userHome)
        + " to name the database this would once have defaulted to."
        + " SEGUE_DB is inherited by any shell started from the owner's profile, so it cannot"
        + " stand in for a flag typed per invocation";
  }
}
