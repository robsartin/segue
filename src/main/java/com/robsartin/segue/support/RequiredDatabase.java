package com.robsartin.segue.support;

import java.nio.file.Path;

/**
 * The refusal a tool with no default database gives when {@code --db} was not typed (#179).
 *
 * <p><b>A sentence, not a path.</b> {@code RetractCli} and {@code OwnCli} have no default database
 * and must not acquire one, and neither may {@code CensusCli}, the third caller — it requires the
 * flag on ADR 60's central clause rather than its consequence, and carries two fences of its own
 * (ADR 63). So this class hands back a {@link String} and never a {@link Path}: there is nothing
 * here any of them could use as a database even by mistake, which is what keeps that boundary
 * meaningful rather than merely literal.
 *
 * <p><b>That is enforced, not merely stated.</b> {@code
 * ArchitectureTest.theClaimToolsTakeTheirDatabaseFromTheFlagAlone} fails the build if {@code
 * retract} or {@code own} takes a {@link Path} out of any {@code support} class — a method's return
 * or a field's type. The rule exists because of this class: it is the one bridge those two packages
 * have into {@code support}, so a {@code Path}-returning method added here would restore the
 * default they gave up while never naming {@link DefaultDatabase}, which is all the sibling rule
 * forbids. Planted exactly that way, the sibling rule stayed green (ADR 60).
 *
 * <p><b>Why it exists at all.</b> The refusal has to name the database the tool would once have
 * defaulted to, or the owner's next command is a lookup rather than a copy-paste - and naming it
 * means resolving it. The first cut of #179 did that with a private copy of the env-or-home rule in
 * each tool, which was a third and fourth copy of the one rule {@link DefaultDatabase} exists to
 * hold. The copies slipped past the design because the fence names {@code DefaultDatabase}
 * literally, and re-implementing a rule never touches the class that owns it. So the resolution
 * happens here, once, by calling {@link DefaultDatabase#resolve} - and the two tools depend on this
 * class instead, not on {@code DefaultDatabase}, which leaves {@code
 * ArchitectureTest.theClaimToolsHaveNoDefaultDatabase} exactly as strong as it was.
 *
 * <p><b>If you are about to add a helper here that hands a claim tool the default path, read this
 * first.</b> A {@link Path} is refused by the build. A {@link String} is not, and that is a known
 * limit rather than an opening: the fence can only see the type, and this class necessarily returns
 * the path as text inside its sentence, so no predicate can tell a sentence from a path spelled
 * out. A {@code String}-returning {@code defaultPath()} used from {@code retract}, {@code own} or
 * {@code census} restores exactly the default #179 removed, with every architecture test green.
 * What stands between that and the codebase is this paragraph and a reviewer, so put it in {@code
 * DefaultDatabase} and let the four tools that keep a default use it there.
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
