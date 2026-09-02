package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeType;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.SourceAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Expansion from MusicBrainz — the second source, and the first that is not Wikidata.
 *
 * <p><b>One pass, not two.</b> {@code WikidataSourceAdapter} needs a forward pass and a reverse one
 * because Wikidata states a relation once, on one end, and reading an entity returns only the
 * claims stated ON it (ADR 36). MusicBrainz states a relation once too, but on the <i>pair</i>, and
 * {@code inc=artist-rels} returns it whichever end you ask — measured on two probes in {@code
 * docs/design/2026-08-30-three-source-adapters.md}. So one call answers the question both of
 * Wikidata's passes exist to answer.
 *
 * <p><b>Direction is read, never normalised away.</b> MusicBrainz reports direction relative to the
 * entity asked about: {@code forward} means the seed is the relation's subject, {@code backward}
 * that it is the object. For {@code member of band} the subject is the member and the object is the
 * band, and P463 ({@link EdgeTypes#MEMBER_OF}) means the same thing — so a {@code backward}
 * relation becomes {@code target MEMBER_OF seed} and a {@code forward} one {@code seed MEMBER_OF
 * target}.
 *
 * <p><b>The reason to read the field is that one relation is reachable from both of its ends, with
 * opposite values.</b> The committed fixture is a group's roster and every {@code member of band}
 * row in it is {@code backward}; asking the same question of one of those members returns the same
 * relation {@code forward}. That is the two probes the design note measured — a Group returning 9
 * {@code backward} and a Person 13 {@code forward}, one call each. Reading the field is what makes
 * both ends of a pair produce the identical edge, and it is why this source needs no reverse pass;
 * assuming a single orientation would make one membership point one way when expanded from the band
 * and the other way when expanded from the member.
 *
 * <p><b>The whitelist is written here rather than derived from {@link EdgeTypes}, and that is the
 * difference from Wikidata.</b> {@code ClaimMapper}'s filter IS the vocabulary, keyed by Wikidata
 * property, because Wikidata is where the vocabulary comes from (ADR 22 clause 3). MusicBrainz
 * names its relation types in its own words, so something has to say that {@code "member of band"}
 * is P463 — and only a relation type someone can justify that way is mapped. An unrecognised type
 * is skipped, not guessed: ADR 38 admits one property at a time, and a real response is dominated
 * by types this source should not ingest at all (87% of one probe was {@code tribute}, and the
 * family relations another returned are third-party personal data ADR 16 says not to collect).
 * <b>Skipping is normal operation here, not an error</b>, which is why nothing is flagged when it
 * happens.
 *
 * <p><b>{@code subgroup} is absent too, and that is now a decision with a record rather than an
 * omission: ADR 55, on a count</b> (<a href="https://github.com/robsartin/segue/issues/142">issue
 * #142</a>). It is how MusicBrainz relates one act to another it is part of — {@code member of
 * band} is not used for that — so it is the only relation this source has that could produce a
 * group-in-group edge. A probe over a sample of the real graph's {@code PERSON} and {@code GROUP}
 * nodes found it <b>twice in 959 relations</b>, on one seed, of which one target resolved to a QID:
 * the whole vocabulary decision would have bought one edge. Both candidate codes — {@link
 * EdgeTypes#MEMBER_OF} (P463) and {@link EdgeTypes#PART_OF} (P361) — are already in use between two
 * groups in this graph, stated by Wikidata, which splits them; MusicBrainz says only {@code
 * subgroup} and draws no such distinction. ADR 55 is the authority on the figures and on why each
 * candidate lost.
 *
 * <p><b>{@code collaboration} is deliberately absent.</b> MusicBrainz states it as a first-class
 * artist relation, and {@code EdgeTypes.COLLABORATED_WITH} exists — but it is registered {@code
 * EdgeType.derived} with a null Wikidata property and a javadoc saying no source states it.
 * Admitting a source-stated collaboration would either falsify that sentence or invent a code, and
 * Wikidata has no general collaboration property to borrow.
 *
 * <p><b>The bound is spent before the bridge is, not after.</b> This source's real cost is not the
 * relation list — that arrives in one response — but the MBID-to-QID lookup per neighbour that
 * {@link MusicBrainzIdentity} performs, against a service that asks for roughly one request a
 * second. So {@code ctx.maxNewEdges()} is applied to the whitelisted relations <i>before</i> any
 * neighbour is resolved. The visible consequence is that a bound of N can yield fewer than N
 * assertions, because a neighbour dropped for want of a QID has already spent its slot; the
 * alternative is a bound that bounds nothing (GAP 5 in the design note).
 *
 * <p><b>Its truncation is arbitrary, and the port cannot say so.</b> ADR 36's Wikidata bound keeps
 * the n most-linked neighbours, ordered by sitelink count, and that ordering is what makes the cut
 * a quality decision as well as a cost one. A MusicBrainz relation carries no prominence signal at
 * all, so the n kept here are simply the n MusicBrainz listed first. {@link ExpandResult#truncated}
 * reports both the same way (GAP 6).
 *
 * <p><b>{@code neighbors()} is filled, but only from an identity that says enough</b> (<a
 * href="https://github.com/robsartin/segue/issues/163">issue #163</a>; ADR 61, which reverses half
 * of ADR 55 and leaves its {@code subgroup} half standing). {@link ExpandResult} treats neighbours
 * as an optimisation an adapter may supply and is explicit that one which does not know is not
 * obliged to guess. This adapter now knows, sometimes, and the guard is what "sometimes" means: see
 * {@link #describes}.
 *
 * <p><b>The history is worth carrying, because the guard is the whole of what it taught.</b> #143
 * asked for a {@code neighbors()} built from the MusicBrainz response alone, and the response does
 * pay for two thirds of a neighbour's identity — every relation in the committed fixture carries
 * {@code artist.type} (22 {@code Person} and 2 {@code Group}, mapping one-to-one onto {@link
 * #DESCRIBED}), and {@link ArtistRelation#targetName} carries the label. The missing third is the
 * expensive one: MusicBrainz classifies an artist without stating Wikidata classes, so {@code
 * instanceOf} would be empty. {@code SegueService} prefers an adapter's neighbour to a fetch and
 * records it <b>whether or not the node already exists</b> (issue #55), and {@code
 * TinkerGraphStore.upsertNode} writes {@code instanceOf} on every upsert, empty included and
 * deliberately so — its own comment says a later claim stating no classes must not leave an earlier
 * claim's behind. So #143's neighbour would not merely decline to add classes; it would remove the
 * ones already there, and give the ones it discovered none. {@code PathRanking.isHub}, {@code
 * CandidateSweep}, {@code rate/Card}, {@code DotWriter} and {@code GraphMlWriter} all read that
 * field, and ADR 42 is the decision that the log keeps the raw classes so a derivation can be
 * revisited at all. <b>ADR 55 refused #143 on that, measured</b>: of the neighbours this adapter
 * resolved, 44% were already in the graph — where the fetch is not spent at all, because it is
 * gated on {@code isNew} — and a further tenth were described by Wikidata's own reverse pass in the
 * same call, against nodes losing or never gaining their classes at a greater rate than that. That
 * refusal still stands, and {@code MusicBrainzNeighbourIdentityTest} still holds it.
 *
 * <p><b>What ADR 61 changes is where the classes come from.</b> ADR 55's own closing sentence named
 * the route that collects the saving without the cost — a bridge that returns classes alongside
 * QIDs, one batched Query Service round trip per 100 neighbours, the shape {@code ReverseClaims}
 * already uses for Wikidata's own neighbours — and said it was a change of its own because it
 * widens {@link MusicBrainzIdentity} and the {@code app} class behind it. That is what {@link
 * MusicBrainzIdentity#identitiesFor} is. So this adapter emits a neighbour when, and only when, the
 * bridge could see a real label and at least one class; anything less is omitted and the caller's
 * fetch happens exactly as before. Measured over the committed fixture's 22 mappable neighbours,
 * that is 22 fetches saved at the same one bridge round trip, and 22 fetches still spent when the
 * bridge describes none of them — {@code NeighbourFetchCountTest} is the measurement and its own
 * positive control.
 *
 * <p><b>The neighbour claim is stamped {@code "wikidata"}, not {@link #SOURCE_ID}.</b> See {@link
 * #toNeighbour}, and {@link SourceAdapter#id()}, whose sentence is scoped to {@link
 * ExpandResult#assertions()} for this reason.
 *
 * <p><b>GAP 7 is unchanged and is the reason the guard is a guard rather than a throw.</b> {@code
 * instanceOf} is a list of QIDs enforced by {@code NodeRecord}'s constructor, so a MusicBrainz type
 * id put there would build a {@code NodeAssertion} cleanly and blow up later inside {@code
 * IngestService.apply}. An <i>empty</i> list has no element to fail on: it goes through, and
 * erases. That is why an undescribed neighbour is omitted rather than emitted class-less.
 *
 * <p><b>Failures degrade rather than propagate</b>, as {@link SourceAdapter#expand} requires: an
 * unreachable MusicBrainz yields a flagged empty result, not a thrown error. <b>So does an
 * unreachable identity bridge, which it did not before</b> (<a
 * href="https://github.com/robsartin/segue/issues/148">issue #148</a>). {@link MusicBrainzIdentity}
 * now declares {@link MusicBrainzIdentityUnavailableException}, and both of its calls are caught
 * here. The distinction that buys is the one this adapter could not draw: {@link
 * MusicBrainzIdentity#mbidFor} returning empty means MusicBrainz holds no record bridged to this
 * seed, and an empty {@link MusicBrainzIdentity#qidsFor} entry means ADR 22 clause 2 declining to
 * reach that neighbour — both normal operation. A bridge that simply could not answer had no third
 * thing to return, so its failure arrived as one of those two and set no flag.
 *
 * <p><b>Every string this adapter puts in a {@link Provenance} is guarded, and this is where each
 * one is guarded</b> (<a href="https://github.com/robsartin/segue/issues/147">issue #147</a>).
 * {@code Provenance}'s compact constructor throws on a tab or a newline in {@code sourceId} or
 * {@code sourceRef}, and that {@code IllegalArgumentException} would escape {@link #expand} —
 * {@code SegueService.expandEntity} has no {@code try} around {@code adapter.expand} — so one
 * malformed character would abort a whole expansion, across every adapter, instead of costing this
 * one its result. Its four components, all of them:
 *
 * <ul>
 *   <li>{@code sourceId} is {@link #SOURCE_ID}, a literal in this file.
 *   <li>{@code assertedAt} is an {@code Instant} from the injected {@link Clock} and {@code
 *       confidence} is the literal {@code 0.80}; neither is a string and neither can carry a
 *       separator.
 *   <li>{@code sourceRef} is built from three strings and every one of them arrives from outside:
 *       the seed's MBID, the relation type, and the neighbour's MBID. The relation type is guarded
 *       by {@link #BY_RELATION_TYPE} — only a key of that map reaches {@link #toAssertion}, and its
 *       keys are literals here. Both MBIDs are guarded by {@link #MBID}: the seed's in {@link
 *       #expand}, before anything is fetched, and the neighbour's in {@link #isMappable}, before
 *       the bound is spent.
 * </ul>
 *
 * <p><b>None of that rests on which bridge is wired, and that is the point of saying it here.</b>
 * The {@code targetQid} guard in {@link #expand} argues that {@link MusicBrainzIdentity} is an
 * interface this package neither implements nor constrains, so malformed input arrives from outside
 * whatever is behind the seam. Both MBIDs were exposed to exactly that argument and neither was
 * checked; what kept them safe was {@code WikidataMusicBrainzIdentity} validating UUIDs in both
 * directions — the one dependency that argument disclaims.
 */
public final class MusicBrainzSourceAdapter implements SourceAdapter {

  private static final String SOURCE_ID = "musicbrainz";

  private static final String WIKIDATA_SOURCE_ID = "wikidata";

  /** MusicBrainz's own name for the relation this adapter maps. */
  private static final String MEMBER_OF_BAND = "member of band";

  /**
   * The whitelist. One entry, and adding a second is a decision to argue rather than a line to
   * write — see the class note.
   */
  private static final Map<String, EdgeType> BY_RELATION_TYPE =
      Map.of(MEMBER_OF_BAND, EdgeTypes.MEMBER_OF);

  /**
   * MusicBrainz is a database of recorded music: it describes artists, who are people and groups.
   * It holds works, places and events too, but this adapter reads {@code artist-rels} only, so
   * those are not kinds it has anything to say about.
   */
  private static final Set<NodeKind> DESCRIBED = EnumSet.of(NodeKind.PERSON, NodeKind.GROUP);

  private static final String FORWARD = "forward";
  private static final String BACKWARD = "backward";

  /**
   * MusicBrainz dates are variable-precision — {@code "1960"}, {@code "1968-08"} and {@code
   * "1960-08-12"} all occur, and on the probe the design note measured, only 1 of 9 {@code begin}
   * values reached a day.
   */
  private static final Pattern DAY_PRECISION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  /**
   * What a MusicBrainz identifier looks like. MBIDs are UUIDs: all 24 {@code artist.id} values in
   * each committed fixture are UUID-shaped, and {@code WikidataMusicBrainzIdentity} already refuses
   * anything else in both directions of the shipped bridge. So this rejects nothing MusicBrainz has
   * been seen to send. It is a shape check and not an existence check, exactly like {@link
   * Qid#looksLikeAQid}: whether MusicBrainz holds that artist is answered by fetching it.
   *
   * <p><b>Spelled here rather than shared with that class</b>, which holds the identical pattern.
   * Neither could import the other's copy: {@code app} depends on {@code musicbrainz} and not the
   * other way round (ADR 32). The third place both can already see is {@code domain}, where {@link
   * Qid} lives — and that is the one package an MBID may not be defined in. ADR 22 clause 2 says
   * source-local identifiers, naming MBIDs explicitly, resolve to a QID in the ingest layer and
   * never appear in the domain; a shared MBID constant there would be exactly the appearance it
   * forbids. What is left is the duplication {@link Qid}'s own javadoc already records for the
   * packages that spell the QID regex themselves, kept for its reason: this validates arriving
   * external input at the point it arrives, rather than enforcing a domain type's invariant.
   */
  private static final Pattern MBID =
      Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

  private final MusicBrainzClient client;
  private final MusicBrainzIdentity identity;
  private final Clock clock;

  /**
   * @param identity the MBID-to-QID bridge, supplied from outside this package so that {@code
   *     musicbrainz} never imports {@code wikidata} (ADR 32; see {@link MusicBrainzIdentity})
   */
  public MusicBrainzSourceAdapter(
      MusicBrainzClient client, MusicBrainzIdentity identity, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String id() {
    return SOURCE_ID;
  }

  @Override
  public boolean supports(NodeKind kind) {
    return DESCRIBED.contains(kind);
  }

  @Override
  public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(ctx, "ctx");

    Optional<String> bridged;
    try {
      bridged = identity.mbidFor(seed.qid());
    } catch (MusicBrainzIdentityUnavailableException e) {
      // Issue #148. Caught here and turned into the flag, not allowed past expand(): the SPI says
      // failures degrade rather than propagate, and SegueService.expandEntity wraps nothing. The
      // reason this catch exists at all is that the empty answer below already means something —
      // "no MusicBrainz record is bridged to this seed" — so before the seam had a failure type,
      // an outage had no way to say anything other than that.
      return ExpandResult.unavailable();
    }
    if (bridged.isEmpty() || !looksLikeAnMbid(bridged.get())) {
      // Two cases, one answer. Either MusicBrainz has no record bridged to this QID, or the bridge
      // answered with something that is not an MBID — which cannot name a MusicBrainz record and,
      // if it carried a tab or a newline, would take the whole expansion down from inside
      // Provenance's constructor (issue #147). Refusing it here also spends no request on a URL
      // that could not have resolved, against a source that asks for one a second.
      //
      // Neither is a failure and neither is a shortfall: there is nothing to fetch, so flagging
      // either boolean would report a problem that is not one.
      return ExpandResult.of(List.of());
    }
    String seedMbid = bridged.get();

    List<ArtistRelation> relations;
    try {
      relations = client.artistRelations(seedMbid);
    } catch (MusicBrainzUnavailableException e) {
      // Swallowed rather than thrown, exactly as WikidataSourceAdapter swallows its own: the
      // eventual caller is a language model, and sourceUnavailable is what lets the tool layer
      // say "MusicBrainz did not answer" instead of "this artist has no members".
      return ExpandResult.unavailable();
    }

    List<ArtistRelation> mappable =
        relations.stream().filter(MusicBrainzSourceAdapter::isMappable).toList();
    List<ArtistRelation> bounded = mappable.stream().limit(ctx.maxNewEdges()).toList();
    boolean truncated = bounded.size() < mappable.size();

    Map<String, BridgedIdentity> bridgedNeighbours;
    try {
      bridgedNeighbours =
          identity.identitiesFor(
              bounded.stream().map(ArtistRelation::targetMbid).distinct().toList());
    } catch (MusicBrainzIdentityUnavailableException e) {
      // The same reasoning one call later, and with more at stake: an empty map is how ADR 22
      // clause 2 declines to reach a neighbour, measured at 49% of them, so a failed batch used to
      // drop every neighbour of this seed while reporting nothing. No assertion has been built
      // yet, so there is nothing partial to keep.
      return ExpandResult.unavailable();
    }

    // One instant for the whole expansion, not one per assertion: everything here was learned from
    // a single response at a single moment, and repeated clock reads would say otherwise (ADR 20).
    Instant assertedAt = clock.instant();
    List<AssertionRecord> assertions = new ArrayList<>();
    Map<String, NodeAssertion> neighbours = new LinkedHashMap<>();
    for (ArtistRelation relation : bounded) {
      BridgedIdentity neighbour = bridgedNeighbours.get(relation.targetMbid());
      String targetQid = neighbour == null ? null : neighbour.qid();
      if (targetQid == null) {
        // ADR 22 clause 2 declining to reach this neighbour, measured at 49% of artist-relation
        // neighbours and mostly tributes, pseudonyms and billing variants. Not a shortfall.
        continue;
      }
      if (!Qid.looksLikeAQid(targetQid)) {
        // GAP 9: AssertionRecord validates neither endpoint, so a non-QID would be logged happily
        // and then reach TinkerGraphStore.requireVertex and throw mid-batch, after the log entry
        // is already written. ClaimMapper refuses a non-QID object id for the same stated reason,
        // in the guard that names that exact failure. This is not a defensive check against a
        // programming error: MusicBrainzIdentity
        // is an interface this package neither implements nor constrains, and whatever supplies
        // it is reading QIDs out of somebody's database — Wikidata's P434 in the shipped wiring,
        // an external-id whose values are contributor-entered. Malformed input arrives from
        // outside either way, which is why the guard does not depend on which bridge is wired.
        //
        // It is one of three such guards rather than the only one, which is what issue #147 was:
        // the class note enumerates what reaches Provenance and says where each part is checked.
        continue;
      }
      assertions.add(toAssertion(seed.qid(), seedMbid, relation, targetQid, assertedAt));
      if (describes(neighbour)) {
        neighbours.putIfAbsent(targetQid, toNeighbour(neighbour, assertedAt));
      }
    }
    return new ExpandResult(
        List.copyOf(assertions), List.copyOf(neighbours.values()), false, truncated);
  }

  /**
   * Whether this relation can become an edge from what MusicBrainz sent — a whitelisted type, a
   * direction that says which way it runs, and a target MBID that can be cited. All three are asked
   * here, before {@code maxNewEdges} is applied, so that a relation which could never become an
   * edge cannot spend a slot a real one could have had.
   *
   * <p>The MBID is the third of those and the newest (issue #147). {@code
   * MusicBrainzClient.parseRelations} already drops a relation whose {@code artist.id} is absent or
   * blank, which is not the same question: a present, non-blank id that is not an MBID goes into
   * {@code sourceRef}, and a tab or a newline in it throws out of {@link Provenance}'s constructor
   * and past {@link #expand}. Asking it here rather than at the assertion also keeps the bound
   * honest, and costs the bridge nothing — an unciteable neighbour is never even asked about.
   *
   * <p>Package-private rather than private so that {@code MusicBrainzProbe} can count what this
   * adapter would contribute to {@code SegueService}'s concatenation from a response it already
   * holds, instead of keeping a second copy of this rule or spending a second request to ask for
   * it.
   */
  static boolean isMappable(ArtistRelation relation) {
    return relation.type() != null
        && BY_RELATION_TYPE.containsKey(relation.type())
        && (FORWARD.equals(relation.direction()) || BACKWARD.equals(relation.direction()))
        && looksLikeAnMbid(relation.targetMbid());
  }

  /**
   * Whether this bridged identity says enough to be worth emitting as a neighbour, which is the
   * whole of the non-erasing guard (issue #163; ADR 61).
   *
   * <p><b>Both halves are erasure, not fastidiousness.</b> {@code SegueService} prefers an
   * adapter's neighbour to a fetch and records it <b>whether or not the node already exists</b>
   * (issue #55), and {@code TinkerGraphStore.upsertNode} writes {@code instanceOf} on every upsert,
   * empty included and deliberately so — its own comment says a later claim stating no classes must
   * not leave an earlier claim's behind. So a neighbour emitted with an empty {@code instanceOf}
   * removes the classes an existing node already had and gives a new one none: exactly what ADR 55
   * refused, measured. A label is the same story one field over: {@code wikibase:label} answers
   * with the bare QID where no English label exists, {@link BridgedIdentity} normalises every
   * unbelievable answer to null, and {@link NodeAssertion} requires a label — so emitting one would
   * not misname the node, it would throw out of {@link #expand} and lose the whole expansion.
   *
   * <p>An identity that fails either half is simply omitted, and {@code SegueService} falls back to
   * the fetch it would have spent anyway. That is what makes the saving a saving rather than a
   * trade: nothing is lost when the bridge could not see enough, and the round trip is only skipped
   * when it had nothing left to buy.
   */
  private static boolean describes(BridgedIdentity neighbour) {
    return neighbour.label() != null && !neighbour.instanceOf().isEmpty();
  }

  /**
   * The neighbour claim, carrying {@code "wikidata"} rather than {@link #SOURCE_ID}.
   *
   * <p>The kind, label and classes are Wikidata's facts — {@code P31} and the label service, read
   * on the bridge's own round trip — and this claim is byte-identical to what {@code ReverseClaims}
   * and {@code WikidataEntityResolver.fetch} would have produced for the same entity, because it is
   * the same claim from the same source. Stamping it {@code "musicbrainz"} would attribute
   * Wikidata's classes to a database that states none. The edge stays MusicBrainz's; only the
   * identity is Wikidata's. See {@link SourceAdapter#id()}, whose sentence is scoped to {@link
   * ExpandResult#assertions()} for this reason.
   */
  private static NodeAssertion toNeighbour(BridgedIdentity neighbour, Instant assertedAt) {
    return new NodeAssertion(
        neighbour.qid(),
        neighbour.kind(),
        neighbour.label(),
        neighbour.instanceOf(),
        new Provenance(WIKIDATA_SOURCE_ID, neighbour.qid(), assertedAt, 1.00));
  }

  /** Whether this string is an MBID, for the two callers that check rather than refuse. */
  private static boolean looksLikeAnMbid(String mbid) {
    return mbid != null && MBID.matcher(mbid).matches();
  }

  private static AssertionRecord toAssertion(
      String seedQid,
      String seedMbid,
      ArtistRelation relation,
      String targetQid,
      Instant assertedAt) {

    EdgeType type = BY_RELATION_TYPE.get(relation.type());
    boolean forward = FORWARD.equals(relation.direction());
    String from = forward ? seedQid : targetQid;
    String to = forward ? targetQid : seedQid;

    LocalDate validFrom = dayPrecision(relation.begin());
    LocalDate validTo = dayPrecision(relation.end());
    if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
      // AssertionRecord rejects an inverted window by throwing, which would take the whole
      // expansion with it. ClaimMapper meets the same hazard on the same two fields and keeps the
      // claim while dropping the nonsense; this follows that precedent rather than re-deciding.
      validFrom = null;
      validTo = null;
    }
    // relation.ended() is deliberately not read. "Ended, date unknown" has no shape in
    // AssertionRecord — validTo is a date or nothing — and a true with no end date would have to
    // become an invented one to be recorded at all.

    // MusicBrainz relations carry no statement id of their own, so the citation is built from the
    // record that was read plus the relation within it: both ends of the pair and the type that
    // joins them, which is what makes the claim findable again.
    String sourceRef = "artist/" + seedMbid + "#" + relation.type() + ":" + relation.targetMbid();

    return new AssertionRecord(
        from,
        to,
        type.code(),
        validFrom,
        validTo,
        // ADR 23: structured, and MusicBrainz states no citation for a relation. 0.80, with no
        // decision to make — the tiers are written as a convention shared by all adapters.
        new Provenance(SOURCE_ID, sourceRef, assertedAt, 0.80));
  }

  /**
   * A MusicBrainz date, or null when it does not reach day precision.
   *
   * <p>The precedent is {@code ClaimMapper.qualifierDate}, which drops a Wikidata time below
   * precision 11 for the same reason and says so: a year- or month-precision date read as a {@link
   * LocalDate} "would feed false day-level precision into {@code validAt()} time-travel queries".
   * The two sources encode precision differently — Wikidata states it as a number, MusicBrainz by
   * how much of the string it sends — but the decision is the same one and is not re-made here.
   */
  private static LocalDate dayPrecision(String raw) {
    if (raw == null || !DAY_PRECISION.matcher(raw).matches()) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      // "1962-13-45" matches the shape and is not a date. MusicBrainz should not send one; a
      // thrown parser error out of expand() would be a worse answer than a missing date.
      return null;
    }
  }
}
