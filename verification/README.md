# Verification scripts — historical slice-0 artefact

**Not part of the current gate.** These replay the Jena adapter's SPARQL
against an equivalent rdflib dataset. They exist because Maven Central was
unreachable where this was written, so the Java adapters could not be
compiled — the queries, which are the risky part, were checked this way
instead, before the build even ran on Maven.

The build now runs on Gradle (Maven was never actually adopted), and
`GraphStoreContract` — the abstract test run against both the Tinker and Jena
adapters — is the real gate those Python scripts stood in for: it exercises
the compiled `JenaGraphStore` directly and runs on every `./gradlew check`.
If you change a SPARQL query in `JenaGraphStore`, `GraphStoreContract` is what
catches a regression now.

Kept for the record of how slice 0's queries were checked before the Java
side could be built at all; not expected to be run again in the ordinary
course of development.

```bash
pip install rdflib
python3 sparql_check.py   # all four queries + the neighbour query
python3 dfs_check.py      # path enumeration semantics, shared by both adapters
```
