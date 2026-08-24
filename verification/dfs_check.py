import sparql_check as sc   # reuses the same rdflib dataset + neighbours()

FAILED = []
def check(what, ok):
    print(f"   [{'PASS' if ok else 'FAIL'}] {what}")
    if not ok: FAILED.append(what)

def enumerate_paths(start, goal, max_hops):
    """Mirrors both adapters: all simple paths up to max_hops, branch stops at goal."""
    cache = {}
    out = []
    def nbrs(q):
        if q not in cache: cache[q] = sc.neighbours(q)
        return cache[q]
    def go(cur, hops_left, path, on_path):
        if hops_left == 0: return
        for nb in nbrs(cur):
            if nb in on_path: continue
            path.append(nb); on_path.add(nb)
            if nb == goal: out.append(list(path))
            else: go(nb, hops_left - 1, path, on_path)
            path.pop(); on_path.discard(nb)
    go(start, max_hops, [start], {start})
    out.sort(key=len)
    return out

print("\n-- Q1 path enumeration (matched Gremlin/Jena semantics)")
hill = enumerate_paths(sc.CAVE, sc.HILLCOAT, 4)
print(f"      Cave -> Hillcoat: {len(hill)} paths, shortest {len(hill[0])-1} hops")
for p in hill[:4]: print("        ", p)
check("shortest Cave-Hillcoat route is 2 hops", len(hill[0]) - 1 == 2)
check("shortest route goes through a film",
      hill[0][1] in (sc.PROPOSITION, sc.ROAD_FILM))

mcc = enumerate_paths(sc.CAVE, sc.MCCARTHY, 4)
print(f"\n      Cave -> McCarthy: {len(mcc)} paths, lengths {sorted({len(p)-1 for p in mcc})}")
for p in mcc[:5]: print("        ", p)
check("both a 1-hop and a longer route exist", 
      1 in {len(p)-1 for p in mcc} and max(len(p)-1 for p in mcc) > 1)
check("the 1-hop route is the model's direct INFLUENCED_BY guess",
      mcc[0] == [sc.CAVE, sc.MCCARTHY])
longer = [p for p in mcc if len(p) - 1 > 1]
check("a longer route runs Cave -> The Road (film) -> The Road (novel) -> McCarthy",
      any(p == [sc.CAVE, sc.ROAD_FILM, sc.ROAD_NOVEL, sc.MCCARTHY] for p in longer))

print()
if FAILED:
    print(f"{len(FAILED)} check(s) FAILED"); raise SystemExit(1)
print("Path enumeration semantics verified - both adapters can return identical results.")
