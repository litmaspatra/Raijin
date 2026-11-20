# Phase 0B Corpus

## Blind-build rule
The corpus must be created **without reading the held-out tasks or private reference solutions**.

Corpus contents are chosen only from a generic predeclared set of common Python standard-library APIs and recurring coding patterns.

Examples of generic topic families:
- pathlib / os / shutil filesystem operations
- json / csv / configparser serialization
- datetime / zoneinfo time handling
- collections / itertools / functools utilities
- re string matching
- sqlite3 parameterized queries and transactions
- subprocess invocation patterns
- tempfile / contextlib resource management
- logging
- dataclasses / enum / typing
- hashlib / secrets
- urllib.parse URL manipulation
- common exception-handling/resource-cleanup patterns
- common stdlib repair patterns

This list is a corpus-authoring scope, not an oracle map.

## Target
Approximately 200 hand-curated, verified Python knowledge items.

Allowed kinds:
- api_fact
- pattern
- procedure
- template
- repair

Every item has a stable corpus ID.

## Oracle mapping happens later
Do not create corpus items to satisfy held-out tasks.

After:
1. held-out set is frozen,
2. corpus is independently built and frozen,

then map each held-out task to already-existing corpus items where a genuine match exists.

Tasks with no matching frozen item are marked `uncovered` and remain in the overall denominator.
