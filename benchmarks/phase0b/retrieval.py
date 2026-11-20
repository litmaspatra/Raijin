from __future__ import annotations

import hashlib
import random
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Protocol, Sequence

from corpus.validate_corpus import load_corpus
from pins import CONTEXT_BUDGET_TOKENS, RRF_K, TOP_K
from schema import HeldOutTask, KnowledgeItem


WORD_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+")
DEF_RE = re.compile(r"^\s*(?:async\s+def|def)\s+[A-Za-z_]\w*\s*\(.*\)\s*(?:->\s*[^:]+)?\s*:\s*$")


@dataclass(frozen=True)
class RankedHit:
    item_id: str
    kind: str
    rank: int
    score: float


class Embedder(Protocol):
    def embed(self, texts: Sequence[str]): ...


class TokenCounter(Protocol):
    def count(self, text: str) -> int: ...
    def truncate(self, text: str, max_tokens: int) -> str: ...


def extract_function_signature(prefix: str) -> str:
    for line in reversed(prefix.splitlines()):
        if DEF_RE.match(line):
            return line.strip()
    return ""


def retrieval_query(task: HeldOutTask) -> str:
    signature = extract_function_signature(task.prefix)
    return task.instruction.strip() + ("\n" + signature if signature else "")


def corpus_document(item: KnowledgeItem) -> str:
    return f"{item.title}\n{item.content.strip()}"


def _fts_terms(query: str) -> list[str]:
    seen: set[str] = set()
    terms: list[str] = []
    for term in WORD_RE.findall(query.lower()):
        if term not in seen:
            seen.add(term)
            terms.append(term)
    return terms


class FTS5Retriever:
    def __init__(self, items: Sequence[KnowledgeItem]):
        self.items = {item.item_id: item for item in items if item.verified}
        self.conn = sqlite3.connect(":memory:")
        self.conn.execute(
            "CREATE VIRTUAL TABLE corpus_fts USING fts5("
            "item_id UNINDEXED, title, content, tokenize='unicode61')"
        )
        self.conn.executemany(
            "INSERT INTO corpus_fts(item_id,title,content) VALUES (?,?,?)",
            [(item.item_id, item.title, item.content) for item in self.items.values()],
        )

    def rank(self, query: str) -> list[RankedHit]:
        terms = _fts_terms(query)
        if not terms:
            return []
        expression = " OR ".join('"' + term.replace('"', '""') + '"' for term in terms)
        rows = self.conn.execute(
            "SELECT item_id, bm25(corpus_fts) AS score "
            "FROM corpus_fts WHERE corpus_fts MATCH ? "
            "ORDER BY score ASC, item_id ASC",
            (expression,),
        ).fetchall()
        return [
            RankedHit(
                item_id=item_id,
                kind=self.items[item_id].kind.value,
                rank=index + 1,
                score=float(-score),
            )
            for index, (item_id, score) in enumerate(rows)
        ]


class MiniLMOnnxEmbedder:
    def __init__(self, model_path: Path, tokenizer_path: Path):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self.np = np
        self.tokenizer = Tokenizer.from_file(str(tokenizer_path))
        self.tokenizer.enable_truncation(max_length=256)
        self.session = ort.InferenceSession(
            str(model_path),
            providers=["CPUExecutionProvider"],
        )
        self.input_names = {x.name for x in self.session.get_inputs()}

    def embed(self, texts: Sequence[str]):
        np = self.np
        encodings = self.tokenizer.encode_batch(list(texts))
        max_len = max(len(e.ids) for e in encodings)
        batch = len(encodings)

        ids = np.zeros((batch, max_len), dtype=np.int64)
        mask = np.zeros((batch, max_len), dtype=np.int64)
        types = np.zeros((batch, max_len), dtype=np.int64)
        for row, enc in enumerate(encodings):
            n = len(enc.ids)
            ids[row, :n] = np.asarray(enc.ids, dtype=np.int64)
            mask[row, :n] = 1
            if enc.type_ids:
                types[row, :n] = np.asarray(enc.type_ids, dtype=np.int64)

        feed = {}
        if "input_ids" in self.input_names:
            feed["input_ids"] = ids
        if "attention_mask" in self.input_names:
            feed["attention_mask"] = mask
        if "token_type_ids" in self.input_names:
            feed["token_type_ids"] = types

        hidden = self.session.run(None, feed)[0]
        weights = mask[..., None].astype(np.float32)
        summed = (hidden * weights).sum(axis=1)
        denom = np.clip(weights.sum(axis=1), 1e-9, None)
        pooled = summed / denom
        norms = np.linalg.norm(pooled, axis=1, keepdims=True)
        return pooled / np.clip(norms, 1e-12, None)


class VectorRetriever:
    def __init__(self, items: Sequence[KnowledgeItem], embedder: Embedder):
        self.items = [item for item in items if item.verified]
        self.embedder = embedder
        self.matrix = embedder.embed([corpus_document(item) for item in self.items])

    def rank(self, query: str) -> list[RankedHit]:
        query_vec = self.embedder.embed([query])[0]
        scores = self.matrix @ query_vec
        ordered = sorted(
            range(len(self.items)),
            key=lambda i: (-float(scores[i]), self.items[i].item_id),
        )
        return [
            RankedHit(
                item_id=self.items[i].item_id,
                kind=self.items[i].kind.value,
                rank=rank,
                score=float(scores[i]),
            )
            for rank, i in enumerate(ordered, 1)
        ]


def reciprocal_rank_fusion(
    fts_hits: Sequence[RankedHit],
    vector_hits: Sequence[RankedHit],
    *,
    k: int = RRF_K,
) -> list[RankedHit]:
    scores: dict[str, float] = {}
    kinds: dict[str, str] = {}
    for hits in (fts_hits, vector_hits):
        for hit in hits:
            scores[hit.item_id] = scores.get(hit.item_id, 0.0) + 1.0 / (k + hit.rank)
            kinds[hit.item_id] = hit.kind
    ordered = sorted(scores, key=lambda item_id: (-scores[item_id], item_id))
    return [
        RankedHit(item_id, kinds[item_id], rank, scores[item_id])
        for rank, item_id in enumerate(ordered, 1)
    ]


class JsonTokenizerCounter:
    def __init__(self, tokenizer_path: Path):
        from tokenizers import Tokenizer
        self.tokenizer = Tokenizer.from_file(str(tokenizer_path))

    def count(self, text: str) -> int:
        return len(self.tokenizer.encode(text, add_special_tokens=False).ids)

    def truncate(self, text: str, max_tokens: int) -> str:
        if max_tokens <= 0:
            return ""
        ids = self.tokenizer.encode(text, add_special_tokens=False).ids[:max_tokens]
        return self.tokenizer.decode(ids, skip_special_tokens=False)


def format_item(item: KnowledgeItem) -> str:
    return f"[{item.item_id}] {item.title}\n{item.content.strip()}"


def comment_context(text: str) -> str:
    if not text.strip():
        return "# (none)"
    return "\n".join(f"# {line}" if line else "#" for line in text.splitlines())


def build_context(
    item_ids: Sequence[str],
    corpus_by_id: dict[str, KnowledgeItem],
    token_counter: TokenCounter,
    *,
    top_k: int = TOP_K,
    budget_tokens: int = CONTEXT_BUDGET_TOKENS,
) -> tuple[str, list[str], int]:
    selected: list[str] = []
    chunks: list[str] = []

    def injected_token_count(raw: str) -> int:
        return token_counter.count(comment_context(raw)) if raw else 0

    for item_id in list(item_ids)[:top_k]:
        item = corpus_by_id[item_id]
        chunk = format_item(item)
        separator = "\n\n" if chunks else ""
        candidate = "".join(chunks) + separator + chunk
        if injected_token_count(candidate) <= budget_tokens:
            if separator:
                chunks.append(separator)
            chunks.append(chunk)
            selected.append(item_id)
            continue

        base = "".join(chunks) + separator + f"[{item.item_id}] {item.title}\n"
        if injected_token_count(base) > budget_tokens:
            break

        remaining_raw_tokens = max(
            0,
            budget_tokens - injected_token_count(base),
        )
        trimmed = token_counter.truncate(item.content.strip(), remaining_raw_tokens)
        while trimmed:
            candidate = base + trimmed
            if injected_token_count(candidate) <= budget_tokens:
                if separator:
                    chunks.append(separator)
                chunks.append(f"[{item.item_id}] {item.title}\n{trimmed}")
                selected.append(item_id)
                break
            ids_to_remove = max(1, injected_token_count(candidate) - budget_tokens)
            current = token_counter.count(trimmed)
            trimmed = token_counter.truncate(trimmed, max(0, current - ids_to_remove))
        break

    context = "".join(chunks)
    count = injected_token_count(context)
    if count > budget_tokens:
        raise AssertionError(f"comment-wrapped context budget exceeded: {count}>{budget_tokens}")
    return context, selected, count


def deterministic_wrong_snippet(
    task_id: str,
    seed: int,
    corpus: Sequence[KnowledgeItem],
    fts_hits: Sequence[RankedHit],
    vector_hits: Sequence[RankedHit],
    excluded_ids: Iterable[str],
) -> KnowledgeItem:
    basis = fts_hits[0] if fts_hits else vector_hits[0]
    target_kind = basis.kind
    excluded = set(excluded_ids)
    candidates = sorted(
        [
            item for item in corpus
            if item.verified and item.kind.value == target_kind and item.item_id not in excluded
        ],
        key=lambda item: item.item_id,
    )
    if not candidates:
        raise ValueError(f"no wrong-snippet candidates for kind {target_kind}")
    digest = hashlib.sha256(f"{seed}:{task_id}".encode("utf-8")).digest()
    rng = random.Random(int.from_bytes(digest[:8], "big"))
    return rng.choice(candidates)


def load_verified_corpus(path: Path) -> list[KnowledgeItem]:
    return [item for item in load_corpus(path) if item.verified]
