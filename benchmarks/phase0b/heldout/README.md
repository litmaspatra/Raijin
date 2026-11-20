# Held-out Tasks

The held-out task set was authored externally, reviewed by the owner, validated and frozen before the official Phase 0B model run.

## Current gate
The set is now immutable benchmark evidence. Do not edit tasks after seeing model output, and never commit the private reference solutions.

Before model evaluation:
1. create at least 60 unique bounded single-hole tasks,
2. include an executable test for every task,
3. assign a knowledge-dependency label,
4. provide private reference solutions separately,
5. obtain user review,
6. run count/distribution and null/reference vacuity validation,
7. freeze the JSONL and record its SHA-256,
8. independently build and freeze the generic corpus **without reading held-out content**,
9. only after both freezes create oracle coverage mapping.

Held-out task rows must not contain oracle/corpus IDs.

Tasks with no match in the later frozen corpus are labelled `uncovered` and remain in the overall denominator.
