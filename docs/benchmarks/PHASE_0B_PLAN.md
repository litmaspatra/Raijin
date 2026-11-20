# Phase 0B — Local Coding Retrieval Benchmark Plan

## Primary question
Does retrieval materially improve a ~0.5B coding model on constrained, held-out Python slot-infill tasks enough to justify the added local retrieval/adapter complexity?

## Primary model format
The primary condition uses **Qwen2.5-Coder-0.5B base** with native FIM tokens.

Pinned inference controls:
- model family: Qwen2.5-Coder-0.5B
- variant: **base**
- GGUF quantization: **Q4_K_M**
- temperature: **0**
- max output tokens: **128**
- deterministic seed where supported
- one completion per task per condition
- no hidden repair loop

Canonical FIM input:

```text
<|fim_prefix|># Raijin task ...
# Instruction: ...
# Retrieved context:
# every retrieved line is comment-prefixed
<existing prefix><|fim_suffix|><existing suffix><|fim_middle|>
```

Retrieved context is inserted in the FIM prefix and **every line is wrapped as a Python comment**.

## Output handling by condition

### Base-FIM conditions
Raw, FTS, hybrid, oracle, and wrong-snippet conditions use **pure FIM assembly**.

The model output is treated as a continuation after the exact prefix:
```text
candidate = prefix + stop_truncated_output + suffix
```

For the base-FIM path:
- do **not** prose-strip,
- do **not** dedent,
- do **not** re-indent,
- truncate at the first model special token,
- trim an accidentally repeated suffix.

Recognized stop/special tokens include:
- `<|endoftext|>`
- `<|file_sep|>`
- `<|fim_prefix|>`
- `<|fim_suffix|>`
- `<|fim_middle|>`

### Chat-style reference conditions
The 1.5B/chat and cloud reference conditions may return fenced or prose-wrapped code.

Their separate preregistered chat normalizer:
1. truncates special tokens,
2. extracts the first fenced Python/code block when present,
3. strips only **whole-line** known prose markers,
4. dedents the code block,
5. applies the scaffold hole indentation to every line.

Identifiers/code such as `answer: int = 0`, `solution: list = []`, or `sure_value = 1` must never be removed as prose.

No model condition receives ad hoc cleanup after results are seen.

## Format pilot gate
Before held-out authoring/freeze or corpus construction, run a **five-task throwaway format pilot** with the real Qwen2.5-Coder-0.5B base Q4_K_M model.

The five pilot tasks:
- are not part of held-out evaluation,
- are never added to the corpus,
- exercise single-line, nested block, if/else, API-call, and suffix-repetition shapes.

Save:
- exact prompt,
- exact raw model output before normalization,
- llama.cpp/model metadata,
- final assembled candidate,
- syntax result.

Normalizer/FIM fixtures may be added only from this throwaway pilot. If the pilot shows a different production output shape, revise the protocol and re-review **before** held-out tasks are authored.

## Held-out task count and distribution
Minimum: **60 unique executable tasks**.

Minimum composition:
- 15 reasoning-only
- 15 API-knowledge
- 20 API-knowledge-plus-adaptation
- 10 algorithmic-reasoning

Duplicate task IDs never count toward the minimum.

## Held-out validation
Every task must:
- fail on the null slot `pass`,
- pass with its private reference solution,
- pass schema validation,
- satisfy count/distribution checks.

Private reference solutions must be stored **outside the Raijin repository**. Only their SHA-256 is recorded in the benchmark manifest. They must never be exposed to corpus construction, retrieval, or model prompts.

Each reference solution is validated twice:
- chat-block assembly, and
- simulated native-FIM assembly, where the first generated line is flush and the remaining lines receive the scaffold hole indentation.

The FIM validation also asserts that `normalize_fim_output` leaves the constructed reference output unchanged before execution.

## Blind corpus rule
The ~200-item retrieval corpus is built **without looking at held-out tasks or private reference solutions**.

Corpus authoring uses only a generic predeclared list of common Python stdlib APIs and recurring coding patterns.

Mandatory sequence:
1. complete/review the format pilot,
2. freeze the held-out set,
3. independently build and freeze the corpus blind to held-out content,
4. hash held-out set and corpus,
5. only then create and freeze the oracle task→corpus mapping,
6. record oracle mapping SHA-256 before any model benchmark run.

If no suitable item already exists in the frozen corpus, the task is labelled **uncovered**.

Every covered oracle row is also frozen with a mapping strength:
- `direct`: the frozen item directly contains the needed API knowledge/pattern,
- `partial`: the frozen item supplies useful but incomplete knowledge and still requires material adaptation.

Report results:
- overall,
- covered,
- uncovered,
- covered split by direct/partial strength.

No post-hoc corpus item may be added to turn an uncovered task into a covered one.

## Initial corpus
Approximately 200 hand-curated verified Python knowledge items from generic stdlib/pattern topic families:
- filesystem/path handling
- serialization/configuration
- datetime/zoneinfo
- collections/itertools/functools
- regular expressions
- sqlite3
- subprocess
- tempfile/contextlib
- logging
- dataclasses/enum/typing
- hashing/secrets
- URL parsing
- common resource/error-handling patterns

No scraping or automatic Knowledge Factory in Phase 0B.

## Systems and controls
A. 0.5B raw — no retrieval  
B. 0.5B + FTS retrieval — **secondary** retrieval condition  
C. 0.5B + hybrid retrieval — **primary** retrieval condition  
D. oracle retrieval — covered tasks only, using frozen mapping  
E. wrong-snippet control — plausible but irrelevant frozen-corpus item  
F. 1.5B reference ceiling  
G. OpenAI-compatible cloud reference ceiling

Do not select the better of B/C after seeing results. **Hybrid is preregistered as primary.**

## Sandbox and execution safety
Model/cloud output is untrusted code.

Candidate execution must run in Docker (or an equivalent reviewed sandbox) with:
- no network,
- 128 MB memory limit,
- 0.5 CPU limit,
- PID limit,
- read-only container root,
- all Linux capabilities dropped,
- `no-new-privileges`,
- writes confined to the disposable `/work` bind mount,
- `TMPDIR=/work/tmp`,
- Python flags `-E -s -B` (not `-I`, because tests must import `candidate.py`),
- **5-second inner timeout** inside the container,
- **20-second outer timeout** for Docker startup/client failure.

Every container receives a unique name. If the outer Docker client timeout fires, the harness explicitly kills and force-removes that named container.

A timeout is a structured benchmark failure and never crashes the run.

## Structured execution result
Every candidate returns:
- `passed`
- `timed_out`
- `resource_killed`
- `stderr`
- `syntax_valid`

Exit code 124 is a timeout. Exit code 137 is reported separately as `resource_killed` because SIGKILL can represent an OOM/resource kill and must not be silently classified as a timeout.

## Validation gates
Before model execution:
- >=60 unique held-out tasks,
- required knowledge-dependency distribution,
- null-slot vacuity check,
- private reference solution passes,
- manifest hashes match exactly.

The manifest hash gate covers:
- held-out JSONL,
- frozen corpus JSONL,
- prompt template,
- frozen oracle mapping,
- external private reference solutions.

A mismatch refuses execution.

## Metrics
- executable-test pass rate
- syntax-valid rate
- retrieval hit@1 / hit@3 / hit@10 where applicable
- prompt tokens
- prefill latency
- generation latency
- total latency
- peak RSS on the PC
- per-task failure category
- covered/uncovered status

## Pre-run statistical amendment
**Timing disclosure:** this decision rule was amended **after the frozen corpus was compared with the approved held-out tasks and the covered/uncovered split was known, but before any benchmark model output was generated**. No retrieval, runner, or benchmark model calls had been made at amendment time.

Hybrid (C) remains the preregistered primary retrieval condition; FTS remains secondary/descriptive.

Primary paired analysis:
- population: **covered tasks only**,
- comparison: hybrid vs raw on the same covered tasks,
- 10,000 paired bootstrap resamples,
- fixed manifest seed,
- two-sided 95% percentile CI for paired pass-rate difference.

Adopt the retrieval-based 0.5B path only if all are true:
1. on covered tasks, hybrid point-estimate lift over raw is **>= +25 percentage points**,
2. on covered tasks, paired-bootstrap 95% CI lower bound is **> +5 percentage points**,
3. on uncovered tasks, hybrid is **no more than 3 net tasks worse** than raw, where net tasks worse = raw pass count minus hybrid pass count,
4. hybrid is worse than raw on **no more than 2 reasoning-only tasks**,
5. oracle pass rate on **covered tasks is >= 60%**.

Report-only metrics that do **not** affect the adoption gate:
- overall hybrid-vs-raw paired lift,
- covered results split by oracle mapping strength:
  - `direct`,
  - `partial`.

FTS is secondary/descriptive and cannot replace hybrid as the primary test after results are seen.

Report raw paired counts as well as rates/CIs.

## PC vs phone
PC latency and RSS are **relative-only comparison metrics**.

They are not phone-performance claims.

Real device latency, RAM, thermal, and battery measurements occur in Phase 1D on the target phone.

## Execution gate
Do not run the held-out benchmark until:
1. format pilot is completed and reviewed,
2. held-out set is approved and frozen,
3. null/reference vacuity validation passes,
4. blind corpus is independently built and frozen,
5. oracle mapping is created only after both freezes and then frozen,
6. external reference-solution hash is recorded,
7. model artifact/configuration is pinned,
8. all manifest hashes validate.


## Retrieval and runner preregistration — before any official model run

This section was committed before any held-out benchmark model call. Retrieval parameters are fixed and must not be tuned against held-out outcomes.

### Reproducible environment pins
- GitHub Actions runner: `ubuntu-24.04`
- Python candidate sandbox: `python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f`
- llama.cpp: release `b11436` with the already-frozen release-asset SHA-256
- MiniLM vector model: `sentence-transformers/all-MiniLM-L6-v2`, ONNX file at revision `10dbd2f06a8baf40ad285b037edda610c1b9a57c`
- MiniLM ONNX SHA-256: `6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452`

### Frozen retrieval query
For A/B/C/E and vector/FTS plumbing, the query is exactly:

```text
task.instruction
<last one-line def/async-def signature found in task.prefix>
```

Only `instruction` and `prefix` may influence this query. `test_code`, private reference solutions, and oracle mappings are forbidden inputs to query construction. Condition D alone may consume the frozen oracle item IDs.

### Corpus representation
Each searchable document is `title + newline + content`. Only frozen, `verified=true` corpus items are indexed.

### FTS condition B
- SQLite FTS5
- `unicode61` tokenizer
- BM25 ranking
- deterministic lexical query normalization: lowercased identifier/number tokens, first-occurrence deduplication, OR combination
- top-k context candidates: 3

### Vector and hybrid condition C
- embedding model: all-MiniLM-L6-v2 via ONNX Runtime CPU
- mean pooling using the attention mask
- L2 normalization
- vector ranking: cosine-equivalent dot product over all 200 normalized corpus embeddings
- hybrid fusion: reciprocal rank fusion
- RRF constant: **k=60**
- fusion inputs: full FTS match ranking plus full 200-item vector ranking
- final top-k: **3**

### Context budget
Retrieved context is capped at **400 tokens**, measured with the Qwen2.5-Coder-0.5B tokenizer pinned to revision `8123ea2e9354afb7ffcc6c8641d1b2f5ecf18301`.

The context builder uses at most three items. The **400-token budget is measured after the context has been transformed into the exact `# ` comment-line form injected into the FIM prompt**. If a third item would exceed that injected budget, only the fitting prefix of that item's content is retained.

### Wrong-snippet condition E
Oracle mappings are not consulted.

1. Use the kind of FTS rank 1; if FTS has no match, use vector rank 1.
2. Exclude FTS top-3 and hybrid top-3 IDs.
3. Select one remaining verified item of the same kind with a deterministic PRNG seeded from the manifest seed plus task ID.

### Conditions
- A: 0.5B raw, no retrieval
- B: 0.5B + FTS5/BM25
- C: 0.5B + hybrid RRF (**primary**)
- D: 0.5B + frozen oracle context, covered tasks only
- E: 0.5B + wrong-snippet control
- F: Qwen2.5-Coder-1.5B base Q4_K_M raw reference, only if runner time allows

### Runner
The official runner is a single `workflow_dispatch` job. It executes conditions sequentially on the same `ubuntu-24.04` runner using llama.cpp `b11436`, fixed seed 0, temperature 0, and the frozen max-output-token budget. Raw conditions A and F perform no retrieval; B performs FTS only; C performs FTS+vector+RRF; D reads only the frozen oracle mapping; E performs retrieval only to define the preregistered same-kind wrong-snippet control.

Each task/condition writes an artifact log containing the full prompt, raw and normalized output, pass flag, structured verification result, retrieval IDs/ranks, selected context IDs, timings, and explicit retrieval/ranking/adaptation/verification failure flags.

Oracle IDs may be used after retrieval for post-hoc failure attribution, but never to form or rank B/C/E retrieval queries.

### Plumbing-test gate
Before review, only the five throwaway pilot tasks may exercise retrieval plumbing. No CI or development test may run retrieval/model code against `heldout_tasks.jsonl`.


## Pre-run runner hardening amendment

Before any official held-out model run, the runner was further hardened:

- `analyze_results.py` applies the already-frozen adoption gate directly from `summary.json` plus held-out/oracle metadata. Its SHA-256 is frozen in the manifest, and synthetic-log tests cover the covered-task gate, uncovered net-regression control, reasoning-only guard, oracle gate, model-error accounting, and missing-result rejection.
- `dry_run=true` is a workflow input. Dry runs are restricted inside `run_benchmark.py` to the five `pilot-*` tasks and use a distinct `phase0b-dry-run-logs` artifact.
- Each llama.cpp call uses `-t 4`.
- The workflow logs `lscpu` and the pinned llama.cpp version into `environment.txt`.
- The timing field is named `model_wall_including_model_load`; it includes process startup/model load as well as generation.
- A failed or timed-out llama.cpp invocation records `model_error`, writes that task/condition log, appends it to `summary.json`, and continues to the next call.
- `summary.json` is rewritten atomically after every task/condition record so partial results survive later failures.
- Wrong-snippet logs include `wrong_snippet_in_oracle` to expose any accidental overlap with the frozen oracle IDs.
- Both downloaded tokenizer files are SHA-256 checked before retrieval or model execution:
  - MiniLM tokenizer: `be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037`
  - Qwen tokenizer: `c0382117ea329cdf097041132f6d735924b697924d6f6fc3945713e96ce87539`

These changes are plumbing/reproducibility changes only. They do not alter the frozen retrieval query, corpus, oracle mapping, statistical gate, or held-out tasks.
