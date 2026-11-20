from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from retrieval import (
    FTS5Retriever,
    JsonTokenizerCounter,
    MiniLMOnnxEmbedder,
    VectorRetriever,
    build_context,
    load_verified_corpus,
    reciprocal_rank_fusion,
    retrieval_query,
)
from schema import HeldOutTask


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--pilot", required=True, type=Path)
    parser.add_argument("--minilm-model", required=True, type=Path)
    parser.add_argument("--minilm-tokenizer", required=True, type=Path)
    parser.add_argument("--context-tokenizer", required=True, type=Path)
    args = parser.parse_args()

    corpus = load_verified_corpus(args.corpus)
    corpus_by_id = {item.item_id: item for item in corpus}
    tasks = [
        HeldOutTask.from_dict(row)
        for row in json.loads(args.pilot.read_text(encoding="utf-8"))
    ]
    if len(tasks) != 5 or any(not task.task_id.startswith("pilot-") for task in tasks):
        raise SystemExit("plumbing smoke is restricted to the five pilot tasks")

    fts = FTS5Retriever(corpus)
    vector = VectorRetriever(
        corpus,
        MiniLMOnnxEmbedder(args.minilm_model, args.minilm_tokenizer),
    )
    counter = JsonTokenizerCounter(args.context_tokenizer)

    rows = []
    for task in tasks:
        query = retrieval_query(task)
        fts_hits = fts.rank(query)
        vector_hits = vector.rank(query)
        hybrid = reciprocal_rank_fusion(fts_hits, vector_hits)
        context, selected, tokens = build_context(
            [hit.item_id for hit in hybrid[:3]],
            corpus_by_id,
            counter,
        )
        if len(selected) > 3 or tokens > 400:
            raise AssertionError("top-k/context budget invariant failed")
        rows.append({
            "task_id": task.task_id,
            "query": query,
            "fts_top3": [h.item_id for h in fts_hits[:3]],
            "vector_top3": [h.item_id for h in vector_hits[:3]],
            "hybrid_top3": selected,
            "context_tokens": tokens,
        })
    print(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
