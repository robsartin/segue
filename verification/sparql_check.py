"""
Validates the SPARQL in JenaGraphStore against the same fixture, using rdflib.

Maven Central is unreachable from this sandbox so the Jena adapter itself cannot
be compiled here. The queries are the risky part, so they are replayed against an
equivalent named-graph dataset in rdflib and checked for the same answers the
(independently verified) Java domain layer produces.
"""
import uuid
from rdflib import Dataset, Graph, URIRef, Literal, Namespace, XSD, RDFS

WD  = "http://www.wikidata.org/entity/"
SG  = Namespace("https://robsartin.com/segue/ns#")
SGP = "https://robsartin.com/segue/prop/"

PREFIXES = """
PREFIX wd:   <http://www.wikidata.org/entity/>
PREFIX sg:   <https://robsartin.com/segue/ns#>
PREFIX sgp:  <https://robsartin.com/segue/prop/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
"""

CAVE, BAD_SEEDS, BIRTHDAY_PARTY, GRINDERMAN = "Q0900001","Q0900002","Q0900003","Q0900004"
ELLIS, BLIXA, NEUBAUTEN, MICK = "Q0900005","Q0900006","Q0900007","Q0900008"
PROPOSITION, HILLCOAT, ASS_ANGEL = "Q0900009","Q0900010","Q0900011"
ROAD_FILM, MCCARTHY, ROAD_NOVEL, PJ = "Q0900012","Q0900013","Q0900014","Q0900015"

NODES = [
    (CAVE,"PERSON","Nick Cave"), (BAD_SEEDS,"GROUP","Nick Cave and the Bad Seeds"),
    (BIRTHDAY_PARTY,"GROUP","The Birthday Party"), (GRINDERMAN,"GROUP","Grinderman"),
    (ELLIS,"PERSON","Warren Ellis"), (BLIXA,"PERSON","Blixa Bargeld"),
    (NEUBAUTEN,"GROUP","Einsturzende Neubauten"), (MICK,"PERSON","Mick Harvey"),
    (PROPOSITION,"WORK","The Proposition"), (HILLCOAT,"PERSON","John Hillcoat"),
    (ASS_ANGEL,"WORK","And the Ass Saw the Angel"), (ROAD_FILM,"WORK","The Road (film)"),
    (MCCARTHY,"PERSON","Cormac McCarthy"), (ROAD_NOVEL,"WORK","The Road (novel)"),
    (PJ,"PERSON","PJ Harvey"),
]

PULL   = "2026-08-01T09:00:00Z"
LASTFM = "2026-08-20T09:00:00Z"
LLM    = "2026-08-22T14:30:00Z"

def wd_(src, ref, vf=None, vt=None): return ("wikidata", ref, PULL, 1.00, vf, vt)
def mb_(ref, vf=None, vt=None):      return ("musicbrainz", ref, PULL, 0.80, vf, vt)

# (from, type, to, sourceId, sourceRef, assertedAt, confidence, validFrom, validTo)
ASSERTIONS = [
    (CAVE,"MEMBER_OF",BAD_SEEDS,"wikidata","S-cave-badseeds",PULL,1.00,"1983-01-01",None),
    (CAVE,"MEMBER_OF",BAD_SEEDS,"musicbrainz","mb-artist-rel-1",PULL,0.80,"1983-01-01",None),
    (CAVE,"MEMBER_OF",BIRTHDAY_PARTY,"wikidata","S-cave-bp",PULL,1.00,"1978-01-01","1983-06-30"),
    (CAVE,"MEMBER_OF",GRINDERMAN,"wikidata","S-cave-grind",PULL,1.00,"2006-01-01","2011-12-31"),
    (BLIXA,"MEMBER_OF",BAD_SEEDS,"wikidata","S-blixa-badseeds",PULL,1.00,"1983-01-01","2003-07-31"),
    (BLIXA,"MEMBER_OF",BAD_SEEDS,"musicbrainz","mb-artist-rel-2",PULL,0.80,"1983-01-01","2003-07-31"),
    (BLIXA,"MEMBER_OF",NEUBAUTEN,"wikidata","S-blixa-neubauten",PULL,1.00,"1980-01-01",None),
    (ELLIS,"MEMBER_OF",BAD_SEEDS,"wikidata","S-ellis-badseeds",PULL,1.00,"1994-01-01",None),
    (ELLIS,"MEMBER_OF",GRINDERMAN,"musicbrainz","mb-artist-rel-3",PULL,0.80,"2006-01-01","2011-12-31"),
    (MICK,"MEMBER_OF",BAD_SEEDS,"wikidata","S-mick-badseeds",PULL,1.00,"1983-01-01","2009-01-31"),
    (MICK,"MEMBER_OF",BIRTHDAY_PARTY,"wikidata","S-mick-bp",PULL,1.00,"1978-01-01","1983-06-30"),
    (CAVE,"WROTE_SCREENPLAY_FOR",PROPOSITION,"wikidata","S-cave-prop-writer",PULL,1.00,None,None),
    (CAVE,"COMPOSED_FOR",PROPOSITION,"wikidata","S-cave-prop-score",PULL,1.00,None,None),
    (CAVE,"COMPOSED_FOR",PROPOSITION,"musicbrainz","mb-release-score-1",PULL,0.80,None,None),
    (ELLIS,"COMPOSED_FOR",PROPOSITION,"wikidata","S-ellis-prop-score",PULL,1.00,None,None),
    (HILLCOAT,"DIRECTED",PROPOSITION,"wikidata","S-hillcoat-prop",PULL,1.00,None,None),
    (HILLCOAT,"DIRECTED",ROAD_FILM,"wikidata","S-hillcoat-road",PULL,1.00,None,None),
    (CAVE,"COMPOSED_FOR",ROAD_FILM,"wikidata","S-cave-road-score",PULL,1.00,None,None),
    (ELLIS,"COMPOSED_FOR",ROAD_FILM,"wikidata","S-ellis-road-score",PULL,1.00,None,None),
    (ROAD_FILM,"BASED_ON",ROAD_NOVEL,"wikidata","S-road-basedon",PULL,1.00,None,None),
    (MCCARTHY,"AUTHORED",ROAD_NOVEL,"wikidata","S-mccarthy-road",PULL,1.00,None,None),
    (CAVE,"AUTHORED",ASS_ANGEL,"wikidata","S-cave-novel",PULL,1.00,None,None),
    (CAVE,"SIMILAR_TO",PJ,"lastfm","lastfm-similar-2026-08",LASTFM,0.50,None,None),
    (CAVE,"COLLABORATED_WITH",PJ,"llm:claude","chat-2026-08-22#a1",LLM,0.30,None,None),
    (CAVE,"INFLUENCED_BY",MCCARTHY,"llm:claude","chat-2026-08-22#a2",LLM,0.30,None,None),
]

def build():
    ds = Dataset(default_union=False)
    default = ds.graph(URIRef("urn:x-rdflib:default"))
    for qid, kind, label in NODES:
        e = URIRef(WD + qid)
        default.add((e, RDFS.label, Literal(label)))
        default.add((e, SG.kind, Literal(kind)))
    for (f, typ, t, src, ref, at, conf, vf, vt) in ASSERTIONS:
        gi = URIRef("urn:assertion:" + str(uuid.uuid4()))
        claim = ds.graph(gi)
        claim.add((URIRef(WD + f), URIRef(SGP + typ), URIRef(WD + t)))
        default.add((gi, SG.source, Literal(src)))
        default.add((gi, SG.sourceRef, Literal(ref)))
        default.add((gi, SG.assertedAt, Literal(at, datatype=XSD.dateTime)))
        default.add((gi, SG.confidence, Literal(str(conf), datatype=XSD.decimal)))
        if vf: default.add((gi, SG.validFrom, Literal(vf, datatype=XSD.date)))
        if vt: default.add((gi, SG.validTo, Literal(vt, datatype=XSD.date)))
    return ds, default

FAILED = []
def check(what, ok):
    print(f"   [{'PASS' if ok else 'FAIL'}] {what}")
    if not ok: FAILED.append(what)

def key(row):
    return (str(row.f)[len(WD):], str(row.p)[len(SGP):], str(row.t)[len(WD):])

ds, default = build()

# ---- hydration query (allEdges) -----------------------------------------
print("\n-- allEdges hydration query")
Q_ALL = PREFIXES + """
SELECT ?f ?p ?t ?src ?ref ?at ?conf ?vf ?vt WHERE {
  GRAPH ?g { ?f ?p ?t }
  ?g sg:source ?src ; sg:assertedAt ?at ; sg:confidence ?conf .
  OPTIONAL { ?g sg:sourceRef ?ref }
  OPTIONAL { ?g sg:validFrom ?vf }
  OPTIONAL { ?g sg:validTo ?vt }
}
"""
rows = list(ds.query(Q_ALL))
edges = {}
for r in rows:
    edges.setdefault(key(r), []).append(str(r.src))
print(f"      {len(rows)} assertion rows -> {len(edges)} edges")
check("returns one row per assertion (25)", len(rows) == 25)
check("folds to 22 distinct edges, matching the Java domain fold", len(edges) == 22)

# ---- Q2 audit -------------------------------------------------------------
print("\n-- Q2 audit: what did last.fm say after 15 Aug?")
Q2 = PREFIXES + """
SELECT DISTINCT ?f ?p ?t WHERE {
  GRAPH ?g { ?f ?p ?t }
  ?g sg:source ?src ; sg:assertedAt ?at .
  FILTER (?src = "lastfm" && ?at >= "2026-08-15T00:00:00Z"^^xsd:dateTime)
}
"""
r2 = [key(r) for r in ds.query(Q2)]
for k in r2: print("      ", k)
check("exactly one last.fm edge", len(r2) == 1)
check("it is Cave SIMILAR_TO PJ Harvey", r2 == [(CAVE, "SIMILAR_TO", PJ)])

print("\n-- Q2b audit: unbacked model claims")
Q2B = PREFIXES + """
SELECT DISTINCT ?f ?p ?t WHERE {
  GRAPH ?g { ?f ?p ?t }
  ?g sg:source ?src ; sg:assertedAt ?at .
  FILTER (?src = "llm:claude" && ?at >= "1970-01-01T00:00:00Z"^^xsd:dateTime)
}
"""
r2b = sorted(key(r) for r in ds.query(Q2B))
for k in r2b: print("      ", k)
check("two model-asserted edges", len(r2b) == 2)

# ---- Q3 time travel -------------------------------------------------------
print("\n-- Q3 time travel: Bad Seeds lineup, June 1984")
Q3 = PREFIXES + """
SELECT DISTINCT ?f ?p ?t WHERE {
  GRAPH ?g { ?f ?p ?t }
  ?g sg:source ?src .
  OPTIONAL { ?g sg:validFrom ?vf }
  OPTIONAL { ?g sg:validTo ?vt }
  FILTER (?f = wd:%s || ?t = wd:%s)
  FILTER (!BOUND(?vf) || ?vf <= "%s"^^xsd:date)
  FILTER (!BOUND(?vt) || ?vt >= "%s"^^xsd:date)
}
"""
r3 = sorted(key(r) for r in ds.query(Q3 % (BAD_SEEDS, BAD_SEEDS, "1984-06-01", "1984-06-01")))
for k in r3: print("      ", k)
members84 = sorted({k[0] for k in r3 if k[1] == "MEMBER_OF"})
check("three members in June 1984", len(members84) == 3)
check("matches the Java answer [Q0900001, Q0900006, Q0900008]",
      members84 == [CAVE, BLIXA, MICK])
r3b = sorted(key(r) for r in ds.query(Q3 % (BAD_SEEDS, BAD_SEEDS, "2010-06-01", "2010-06-01")))
members10 = sorted({k[0] for k in r3b if k[1] == "MEMBER_OF"})
print("      June 2010:", members10)
check("matches the Java answer [Q0900001, Q0900005]", members10 == [CAVE, ELLIS])

# ---- Q4 corroboration -----------------------------------------------------
print("\n-- Q4 corroboration: 2+ distinct sources")
Q4 = PREFIXES + """
SELECT ?f ?p ?t (COUNT(DISTINCT ?src) AS ?n) WHERE {
  GRAPH ?g { ?f ?p ?t }
  ?g sg:source ?src .
}
GROUP BY ?f ?p ?t
HAVING (COUNT(DISTINCT ?src) >= 2)
"""
r4 = sorted(key(r) for r in ds.query(Q4))
for k in r4: print("      ", k)
check("exactly three corroborated edges", len(r4) == 3)
check("matches the Java answer", r4 == sorted([
    (CAVE, "MEMBER_OF", BAD_SEEDS),
    (BLIXA, "MEMBER_OF", BAD_SEEDS),
    (CAVE, "COMPOSED_FOR", PROPOSITION)]))

# ---- Q1 neighbours (BFS building block) ----------------------------------
print("\n-- Q1 neighbour query (the BFS building block)")
QN = PREFIXES + """
SELECT DISTINCT ?other WHERE {
  { GRAPH ?g { ?e ?p ?other } }
  UNION
  { GRAPH ?g { ?other ?p ?e } }
}
"""
def neighbours(qid):
    q = QN.replace("?e", f"<{WD}{qid}>")
    out = []
    for r in ds.query(q):
        u = str(r.other)
        if u.startswith(WD): out.append(u[len(WD):])
    return sorted(set(out))

n = neighbours(CAVE)
print("      Nick Cave ->", n)
check("Cave's neighbourhood spans music, film and literature",
      BAD_SEEDS in n and PROPOSITION in n and ASS_ANGEL in n)

# BFS, exactly as the Java adapter does it
def bfs(start, goal, max_hops):
    from collections import deque
    parents, visited, frontier, depth, found = {}, {start}, deque([start]), 0, False
    while frontier and depth < max_hops and not found:
        level = []
        for cur in frontier:
            for nb in neighbours(cur):
                if nb in visited: continue
                level.append(nb)
                parents.setdefault(nb, set()).add(cur)
                if nb == goal: found = True
        visited.update(level)
        frontier = deque(dict.fromkeys(level))
        depth += 1
    if not found: return []
    paths = []
    def walk(cur, suffix):
        path = [cur] + suffix
        if cur == start: paths.append(path); return
        for p in parents.get(cur, ()): walk(p, path)
    walk(goal, [])
    return paths

p_hill = bfs(CAVE, HILLCOAT, 4)
print("      Cave -> Hillcoat:", p_hill)
check("Cave reaches Hillcoat in 2 hops (music person -> film -> film person)",
      p_hill and all(len(p) == 3 for p in p_hill))
check("the connection routes through a film",
      all(p[1] in (PROPOSITION, ROAD_FILM) for p in p_hill))

p_mcc = bfs(CAVE, MCCARTHY, 4)
print("      Cave -> McCarthy:", p_mcc)
check("the model's INFLUENCED_BY guess creates a 1-hop shortcut",
      any(len(p) == 2 for p in p_mcc))

print()
if FAILED:
    print(f"{len(FAILED)} SPARQL check(s) FAILED:")
    for f in FAILED: print("  -", f)
    raise SystemExit(1)
print("All SPARQL checks passed.")
