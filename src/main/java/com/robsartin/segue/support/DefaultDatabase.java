package com.robsartin.segue.support;

import java.nio.file.Path;

/**
 * One resolution, where six dev tools used to each carry their own copy of it.
 *
 * <p><b>It lives in {@code support}</b>, following the precedent {@code ArchitectureTest} already
 * records for {@code QidList}: shared logic moves here rather than let it create a dependency
 * between two dev-tool packages, each of which carries its own ArchUnit fence.
 *
 * <p><b>Two of the six tools, {@code RetractCli} and {@code OwnCli}, deliberately do not use this
 * class, and never call {@link #resolve}.</b> They append a first-person claim about the world, and
 * the default this class resolves is exactly the hole issue #179 closes — an agent's shell inherits
 * {@code SEGUE_DB} from the owner's profile, so the environment variable cannot stand in for a flag
 * typed per invocation. Those two tools now <b>require {@code --db}</b> and have no default left to
 * resolve: neither carries a copy of this logic any more, and each reads {@code SEGUE_DB} for one
 * purpose only, to quote the path it would once have used back in its refusal. <b>Two ArchUnit
 * rules now hold that absence rather than intend it</b>: {@code
 * ArchitectureTest.theClaimToolsHaveNoDefaultDatabase} forbids either package from depending on
 * this class at all, and {@code ArchitectureTest.theClaimToolsTakeTheirDatabaseFromTheFlagAlone}
 * forbids taking a {@link Path} out of {@code support} by any route — including through {@link
 * RequiredDatabase}, which does call {@link #resolve} and which both tools do depend on. See ADR
 * 60.
 *
 * <p><b>Pure.</b> No {@code System.getenv} and no {@code System.getProperty} inside it — the four
 * tools that still keep a default call {@link #resolve} exactly as they called their own copy of
 * this logic: {@code resolve(flag, System.getenv("SEGUE_DB"), System.getProperty("user.home"))}.
 */
public final class DefaultDatabase {

  private DefaultDatabase() {}

  /**
   * The database to use: the {@code --db} flag's value if one was given, otherwise {@code SEGUE_DB}
   * if it is set and not blank, otherwise {@code ${user.home}/.segue/segue.db}.
   *
   * <p>The flag's own value is never blank-checked — whatever it carries wins outright, exactly as
   * every one of the six tools this class replaces did. Only the environment variable gets the
   * blank check, because an empty {@code SEGUE_DB=} is indistinguishable from unset to anyone
   * reading a shell's exported variables.
   *
   * @param flagValueOrNull the value the caller's {@code --db} flag parsed to, or {@code null} if
   *     it was not given
   * @param segueDbEnvOrNull {@code System.getenv("SEGUE_DB")}, passed in by the caller so this
   *     method stays pure
   * @param userHome {@code System.getProperty("user.home")}, passed in for the same reason
   */
  public static Path resolve(String flagValueOrNull, String segueDbEnvOrNull, String userHome) {
    if (flagValueOrNull != null) {
      return Path.of(flagValueOrNull);
    }
    return segueDbEnvOrNull != null && !segueDbEnvOrNull.isBlank()
        ? Path.of(segueDbEnvOrNull)
        : Path.of(userHome, ".segue", "segue.db");
  }
}
