# MusicBrainz Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A second source ingests through the existing SPI, so ADR 25's central claim — *adding a source must not require touching the graph layer* — is tested rather than asserted.

**Architecture:** A new `musicbrainz` package beside `wikidata`, implementing `SourceAdapter` only. Identity crosses MBID↔QID through an interface the package declares **for itself**, implemented outside it, so `musicbrainz` imports nothing from `wikidata` and the seam is visible. Two adapters then assert the same edge, which makes ADR 23's corroboration count real for the first time.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit, `java.net.http`. No new dependency.

## Global Constraints

- **Issue #91.** Branch `91-musicbrainz-adapter`. Read the issue **and its 2026-08-29 comment** — that comment chose the source and is the argument.
- **The acceptance criterion is a discovery, not a shape.** A second source must ingest **without touching `domain`, `port`, `tinker`, `jena` or `ingest`**. `mcp`, `support` and configuration are not on that list and may be touched. **If the constraint cannot be met, name the leak and stop — do not work around it silently.** #91 says so explicitly: *"If that proves impossible, the leak is named and ADR 25 amended — that is a valuable finding."* Two prior issues (#88, #81) shipped as documented refusals and were worth more than a forced fix.
- **No network in `./gradlew check`.** Every test runs off fixtures. Live verification is a separate dev-side step.
- MusicBrainz `ws/2` needs **no API key** — verified live. It requires a meaningful `User-Agent` and about **1 request per second**. Never send an email address in that header (ADR 16).
- **Never open `~/.segue/segue.db`.** Copy it for any measurement and report the real file's mtime unchanged.
- **This repo is PUBLIC and the owner's ratings are personal data** (ADR 33, ADR 51). Aggregate figures are fine. **Never name an entity as something the owner rated, likes or is known for.** A branch did exactly that and its history had to be rewritten before pushing.
- Stage by explicit path. NEVER `git add -A`; an untracked `mad.vcf` must never be staged.
- `./gradlew check` green **before every commit** — green at every step. Run long commands **blocking**.
- `domain` has no third-party dependencies.
- TDD: failing test first, run it, **watch it fail for the right reason, and record what it said**.

## What is already verified, so you need not re-derive it

- `SourceAdapter` is `id()`, `supports(NodeKind)`, `expand(NodeRecord, ExpandContext) → ExpandResult`.
- `SourceAdapters` is `record SourceAdapters(List<SourceAdapter> all)` — already plural. `SegueService:211` iterates `adapters.all()`.
- **`SegueService` holds a single `EntityResolver`, not a collection** (`resolver.search` at :102, `resolver.fetch` at :123 and :259). The expand path is pluggable; the resolve path is not. **This asymmetry is the most likely leak and Task 1 must report on it.**
- `NodeRecord` is `(qid, kind, label, instanceOf)` — it carries **no external identifier**, so an adapter handed a seed must obtain the MBID itself.
- `MEMBER_OF` is already registered: `EdgeType.direct("MEMBER_OF", "P463", "member of")`. MusicBrainz's `member of band` maps onto it, so **this source does not force ADR 22's clause 3** — which is why it was chosen first.
- `Provenance` is `(sourceId, sourceRef, assertedAt, confidence)` and `EdgeRecord.corroboration()` counts **distinct** sources. Corroboration therefore needs no new mechanism, only a second asserter.
- `KindMapper` lives in `wikidata/` and is referenced from `seed`, `ingest`, `ratings`, `mcp`, `support`, `export` and `wikidata`. ADR 42 flagged this. The `domain/Retractions.java` hit is **javadoc only** — ADR 18's purity rule is intact; do not report it as a violation.

---

### Task 1: Design three, and name what each needs

**Files:**
- Create: `docs/design/2026-08-30-three-source-adapters.md`

No production code in this task. **It comes first deliberately:** designing three exposes the SPI's gaps, and building one before that design would lock in a shape chosen by the easiest source. Same argument as ADR 18 (two engines built, one chosen) and ADR 38 (one property at a time).

- [ ] **Step 1: Read the SPI and both existing implementors**

`port/SourceAdapter.java`, `port/EntityResolver.java`, `port/ExpandResult.java`, `port/ExpandContext.java`, `port/SourceAdapters.java`, and `wikidata/WikidataSourceAdapter.java` + `wikidata/WikidataEntityResolver.java`. Read them; do not describe them from this plan.

- [ ] **Step 2: Write the design note**

For **MusicBrainz**, **Open Library** and **OpenStreetMap**, each in the same shape:

- What it is authoritative for, and which of the owner's stated domains it reaches.
- **Where its relations live** — MusicBrainz states them on the artist; Wikidata states creative roles on the work, which is why ADR 36 needed a whole reverse-lookup pass. Say for each source which shape it has, because that is what the SPI is being tested against.
- **Its identity, and the bridge to a QID** — MBIDs via `P434`, OLIDs, OSM ids. ADR 22 clause 2 requires source-local ids to resolve to a QID during ingest and **names MBIDs explicitly**. Check that claim in the ADR rather than trusting it here.
- **Whether it forces ADR 22 clause 3** (vocabulary borrowed from Wikidata properties). MusicBrainz does not. Establish whether Open Library and OSM do, and say what would have to be decided if they did.
- **What it would need from the SPI that the SPI does not offer.** This is the point of the task.

Close with a section, **"What three sources say about the SPI"**, listing every gap found. At minimum, resolve whether the single-`EntityResolver` asymmetry above is a real blocker for a second source or merely an unused capability. **If a source needs something the SPI cannot express, say so plainly.**

- [ ] **Step 3: Commit**

```bash
git add docs/design/2026-08-30-three-source-adapters.md
git commit -m "Design three source adapters before building one (#91)"
```

---

### Task 2: The client, off fixtures

**Files:**
- Create: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzClient.java`
- Create: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzUnavailableException.java`
- Test: `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzClientTest.java`
- Fixture: `src/test/resources/musicbrainz/artist-with-relations.json`

**Interfaces:**
- Produces: `MusicBrainzClient.artistRelations(String mbid) → List<ArtistRelation>` where `ArtistRelation` is a record `(String targetMbid, String type, String direction, String targetName)`.

Model it on `wikidata/WikidataClient.java` — read that first and follow its conventions for timeouts, error handling and its unavailable-exception. Do not invent a second style.

- [ ] **Step 1: Capture a real fixture, once**

Fetch one artist with `inc=artist-rels` live, with a proper `User-Agent`, and save the JSON to the fixture path. **Pick a group that is not on the owner's known-list** — the fixture is committed to a public repo and ADR 51 governs what may be named. A well-known band with several `member of band` relations is ideal.

Record in your report which entity you chose and why it is safe.

- [ ] **Step 2: Write the failing test**

```java
  @Test
  @DisplayName("should return every artist relation when the response states several")
  void shouldReturnEveryArtistRelationWhenTheResponseStatesSeveral() {
    MusicBrainzClient client = MusicBrainzClient.readingFrom(fixture("artist-with-relations.json"));

    List<ArtistRelation> relations = client.artistRelations("<the fixture's mbid>");

    assertThat(relations).isNotEmpty();
    assertThat(relations).allSatisfy(r -> assertThat(r.targetMbid()).isNotBlank());
    assertThat(relations).extracting(ArtistRelation::type).contains("member of band");
  }
```

Add a second test pinning that a relation with **no artist target** is skipped rather than throwing — MusicBrainz relations can point at works, labels and URLs, and the response carries them all.

- [ ] **Step 3: Run and watch them fail.** Record the compile error.

- [ ] **Step 4: Implement**

Parse with the JSON library already on the classpath — check `WikidataClient` for which, and use the same one. Rate limiting belongs here: one request per second, and say in the javadoc that the limit is MusicBrainz's stated requirement rather than a guess.

- [ ] **Step 5: Run the gate and commit**

```bash
git add src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzClient.java \
        src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzUnavailableException.java \
        src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzClientTest.java \
        src/test/resources/musicbrainz/artist-with-relations.json
git commit -m "Read artist relations from MusicBrainz, off a fixture (#91)"
```

---

### Task 3: Identity, and the seam that keeps `wikidata` out of `musicbrainz`

**Files:**
- Create: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzIdentity.java`
- Test: `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzIdentityTest.java`

**Interfaces:**
- Produces: an interface **declared in the `musicbrainz` package**:

```java
public interface MusicBrainzIdentity {
  Optional<String> mbidFor(String qid);
  Map<String, String> qidsFor(Collection<String> mbids);
}
```

**Why this shape, and it is the whole architectural bet of this task.** `musicbrainz` must not import `wikidata`, or the second adapter is welded to the first. It also must not add a type to `port/`, which the acceptance criterion forbids. So the package declares what it needs and someone outside supplies it. `qidsFor` is batched because the alternative is one round trip per neighbour and the measured neighbourhood was 387 across 40 seeds.

- [ ] **Step 1: Write the failing test**

Drive the interface with a hand-built stub, not a Wikidata implementation:

```java
  @Test
  @DisplayName("should drop a neighbour with no QID when the mapping does not know it")
  void shouldDropANeighbourWithNoQidWhenTheMappingDoesNotKnowIt() {
    MusicBrainzIdentity identity =
        StubIdentity.of(Map.of("mbid-known", "Q900001"));

    Map<String, String> resolved =
        identity.qidsFor(List.of("mbid-known", "mbid-unknown"));

    assertThat(resolved).containsExactly(entry("mbid-known", "Q900001"));
  }
```

**That dropping is ADR 22 clause 2 working, not a gap.** The measurement found 49% of artist-relation neighbours carry no QID, and a sample showed them to be tribute bands, pseudonyms, billing variants and relatives — material worth not reaching. Say so in the javadoc, with the figure.

- [ ] **Step 2: Run and watch it fail.** Record the message.

- [ ] **Step 3: Implement the interface and the stub.** No Wikidata-backed implementation yet — that is Task 5's wiring.

- [ ] **Step 4: Run the gate and commit**

---

### Task 4: The adapter

**Files:**
- Create: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzSourceAdapter.java`
- Test: `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzSourceAdapterTest.java`

**Interfaces:**
- Consumes: `MusicBrainzClient`, `MusicBrainzIdentity`.
- Produces: `MusicBrainzSourceAdapter implements SourceAdapter` with `id()` returning `"musicbrainz"`.

- [ ] **Step 1: Write the failing tests**

Cover, each as its own test:

- `supports(kind)` is true for the kinds MusicBrainz actually describes and false for the rest. **Iterate `NodeKind.values()`** so a seventh kind is covered automatically rather than silently escaping — `ExpansionBoundsTest` uses this shape; follow it.
- `expand` on a seed whose MBID is unknown returns an **empty** result rather than throwing.
- `expand` emits one assertion per resolvable relation, each carrying `sourceId` `"musicbrainz"` and a `sourceRef` that identifies the MusicBrainz record.
- A relation whose neighbour has no QID produces **no** assertion.
- `ExpandContext.maxNewEdges` is honoured, and exceeding it sets `truncated` — check how `WikidataSourceAdapter` reports this and match it, because issue #65's rule is that a bound which can bite must be reported by the result that hit it.
- The client being unavailable sets `sourceUnavailable` rather than propagating.

- [ ] **Step 2: Run and watch them fail.** Record the messages.

- [ ] **Step 3: Implement.** Map `member of band` to `EdgeTypes.MEMBER_OF`. **Map only what you can justify** — an unrecognised relation type is skipped, not guessed. ADR 38's precedent is one property at a time.

- [ ] **Step 4: Run the gate and commit**

---

### Task 5: Wire it, and make corroboration real

**Files:**
- Modify: wherever `SourceAdapters` is constructed (find it; it is outside the forbidden packages)
- Create: the Wikidata-backed `MusicBrainzIdentity` implementation, **outside** `musicbrainz`
- Test: `src/test/java/com/robsartin/segue/musicbrainz/CorroborationAcrossSourcesTest.java`
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`

- [ ] **Step 1: Write the failing corroboration test**

Two adapters assert the same edge over the same pair; the projected `EdgeRecord` reports `corroboration() == 2`. This is #91's fourth acceptance criterion and **the first time ADR 23's corroboration has ever been exercised** — today every claim comes from one source. Drive it through the projector with fixtures, no network.

- [ ] **Step 2: Write the failing ArchUnit rule**

A rule that `musicbrainz` does not depend on `wikidata`, in the style of the existing rules — read several before writing one, and give it a `.because()` that states the reason, not the mechanism. **This rule is the executable form of this whole issue's claim**, so it matters more than most.

- [ ] **Step 3: Run both and watch them fail.** Record the messages.

- [ ] **Step 4: Implement the wiring and the identity implementation**

The Wikidata-backed `MusicBrainzIdentity` uses `P434`. Put it where it can see both sides without either package seeing the other.

**If wiring a second adapter turns out to require a change inside `domain`, `port`, `tinker`, `jena` or `ingest`, STOP.** Do not make the change. Record exactly which file and which line forced it, and carry it into Task 6 as the finding. That outcome closes the issue honestly and is worth more than a green build.

- [ ] **Step 5: Run the gate and commit**

---

### Task 6: Verify against reality, then record it

**Files:** an ADR (confirm the next number with `ls docs/adr/ | tail -3`), `docs/adr/README.md`, `docs/developer-guide.md`, `CLAUDE.md`.

- [ ] **Step 1: Prove the identity mapping on real entities, outside the test suite**

#91 requires *"identity mapping between the two sources works on real entities"*, and `check` must stay offline. So run a live probe as a dev-side step — the repo already keeps such things as Gradle tasks (ADR 40's argument). Report how many of a real sample resolved, and compare against the measured 51%.

Use a **copy** of the database if you need one at all.

- [ ] **Step 2: Write the ADR**

Record:

- **The decision** — MusicBrainz as the second source, and why it was chosen over Open Library and OSM despite #91's body favouring Open Library: it does not force ADR 22's clause 3, and it states relations on the artist rather than the work, which is the shape that actually tests the SPI. Cite the issue comment.
- **The verdict on ADR 25**, which is the reason this issue exists. Did a second source touch the graph layer? Answer it plainly, name every file outside `musicbrainz` that changed and why, and if the seam leaked, **amend ADR 25** with a dated amendment rather than editing it.
- **The identity finding** — that ADR 22 stays, and the 49% it excludes is largely tribute acts, pseudonyms and billing variants. Give the figures and the sample's character, and note the instrument caveat the measurement recorded rather than only the headline.
- **What corroboration turned out to mean** in practice, now it is real rather than theoretical.
- **The alternatives and why each lost** — Open Library and OSM, from Task 1's design note. An ADR listing only the winner is a note, not a decision record.
- **What this does not settle:** the two unbuilt adapters, and clause 3, which neither this source nor this ADR forces.

**One warning from this repository's recent history.** Nine false generalisations have been recorded across recent issues, every one a sentence about a *group* written from memory rather than from the files, and several "fixes" introduced narrower false ones. **Any sentence claiming something about a set must be verified member by member by opening each file, or rewritten so it does not span a set.** Cite code as the authority; never mirror it.

- [ ] **Step 3: Run the gate and commit**

---

## Self-Review

**Spec coverage.** #91's acceptance maps to: ingests without touching the five packages → Task 5 Step 4 and the ArchUnit rule in Step 2; the leak named and ADR 25 amended if impossible → Task 5's stop condition and Task 6; identity mapping on real entities → Task 6 Step 1; corroboration on at least one edge → Task 5 Step 1; `check` green with no network → every task, fixtures throughout.

**Verified against source rather than inferred.** `SourceAdapters` is already a plural record and `SegueService:211` iterates it; `SegueService` holds one `EntityResolver` (:102, :123, :259); `NodeRecord` carries no external id; `MEMBER_OF`/`P463` is registered; `Provenance` carries `sourceId` and `EdgeRecord.corroboration()` counts distinct sources.

**A judgement worth a reviewer's eye.** Task 3 puts the identity interface in `musicbrainz` rather than `port`, because `port` is on the forbidden list and importing `wikidata` would weld the adapters together. If a reviewer thinks that dodges the constraint rather than satisfying it, that argument should happen at Task 3 and not after Task 5.

**The honest risk.** This plan assumes the seam holds. If it does not, Tasks 5 and 6 change shape completely — and that is the issue's second acceptance criterion, not a failure of the plan.
