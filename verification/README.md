# Verification scripts

These replay the Jena adapter's SPARQL against an equivalent rdflib dataset.
They exist because Maven Central was unreachable where this was written, so the
Java adapters could not be compiled — the queries, which are the risky part,
were checked this way instead.

```bash
pip install rdflib
python3 sparql_check.py   # all four queries + the neighbour query
python3 dfs_check.py      # path enumeration semantics, shared by both adapters
```

Both should print all PASS. If you change a SPARQL query in `JenaGraphStore`,
change it here too and re-run — it's faster than a Maven round trip.
