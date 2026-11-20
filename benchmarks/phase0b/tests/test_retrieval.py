import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from retrieval import (
    FTS5Retriever,
    RankedHit,
    build_context,
    comment_context,
    deterministic_wrong_snippet,
    extract_function_signature,
    load_verified_corpus,
    reciprocal_rank_fusion,
    retrieval_query,
)
from schema import HeldOutTask


PILOT_PATH = ROOT / "pilot" / "tasks.json"
CORPUS_PATH = ROOT / "corpus" / "corpus.jsonl"


class WordCounter:
    def count(self, text):
        return len(text.split())

    def truncate(self, text, max_tokens):
        return " ".join(text.split()[:max_tokens])


class RetrievalPlumbingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tasks = [HeldOutTask.from_dict(row) for row in json.loads(PILOT_PATH.read_text())]
        cls.corpus = load_verified_corpus(CORPUS_PATH)
        cls.by_id = {item.item_id: item for item in cls.corpus}
        if len(cls.tasks) != 5 or any(not t.task_id.startswith("pilot-") for t in cls.tasks):
            raise AssertionError("retrieval plumbing tests must use exactly the five pilot tasks")

    def test_query_is_instruction_plus_function_signature_only(self):
        task = self.tasks[0]
        query = retrieval_query(task)
        self.assertEqual("Return a plus b.\ndef add(a, b):", query)
        changed = HeldOutTask(
            task_id=task.task_id,
            language=task.language,
            instruction=task.instruction,
            prefix=task.prefix,
            suffix="different",
            test_code="raise AssertionError('forbidden retrieval input')",
            knowledge_dependency=task.knowledge_dependency,
        )
        self.assertEqual(query, retrieval_query(changed))

    def test_signature_extraction_uses_last_function_signature(self):
        prefix = "def first(x):\n    pass\n\ndef second(y: int) -> int:\n    "
        self.assertEqual("def second(y: int) -> int:", extract_function_signature(prefix))

    def test_fts5_bm25_returns_ranked_verified_items_for_all_pilots(self):
        fts = FTS5Retriever(self.corpus)
        for task in self.tasks:
            hits = fts.rank(retrieval_query(task))
            self.assertTrue(hits)
            self.assertEqual(list(range(1, len(hits) + 1)), [h.rank for h in hits])
            self.assertTrue(all(self.by_id[h.item_id].verified for h in hits))

    def test_rrf_uses_k_60(self):
        a = [RankedHit("a", "api_fact", 1, 1.0), RankedHit("b", "pattern", 2, 0.5)]
        b = [RankedHit("b", "pattern", 1, 0.9), RankedHit("a", "api_fact", 2, 0.8)]
        fused = reciprocal_rank_fusion(a, b, k=60)
        self.assertAlmostEqual(1 / 61 + 1 / 62, fused[0].score)
        self.assertEqual(["a", "b"], sorted(h.item_id for h in fused))

    def test_context_is_top_three_and_never_over_400_tokens(self):
        ids = [item.item_id for item in self.corpus[:3]]
        context, selected, count = build_context(
            ids, self.by_id, WordCounter(), top_k=3, budget_tokens=400
        )
        self.assertEqual(ids, selected)
        self.assertLessEqual(count, 400)
        self.assertLessEqual(WordCounter().count(comment_context(context)), 400)
        self.assertIn(ids[0], context)

    def test_wrong_snippet_is_deterministic_same_kind_and_excludes_retrieved(self):
        fts = FTS5Retriever(self.corpus)
        task = self.tasks[3]
        fts_hits = fts.rank(retrieval_query(task))
        vector_stub = list(reversed(fts_hits))
        excluded = {h.item_id for h in fts_hits[:3]}
        x = deterministic_wrong_snippet(task.task_id, 0, self.corpus, fts_hits, vector_stub, excluded)
        y = deterministic_wrong_snippet(task.task_id, 0, self.corpus, fts_hits, vector_stub, excluded)
        self.assertEqual(x.item_id, y.item_id)
        self.assertEqual(fts_hits[0].kind, x.kind.value)
        self.assertNotIn(x.item_id, excluded)

    def test_context_budget_is_measured_after_comment_wrapping(self):
        class TightCounter:
            def count(self, text):
                return len(text.split())
            def truncate(self, text, max_tokens):
                return " ".join(text.split()[:max_tokens])

        ids = [self.corpus[0].item_id]
        context, selected, count = build_context(
            ids,
            self.by_id,
            TightCounter(),
            top_k=1,
            budget_tokens=12,
        )
        self.assertLessEqual(TightCounter().count(comment_context(context)), 12)
        self.assertEqual(count, TightCounter().count(comment_context(context)))


if __name__ == "__main__":
    unittest.main()
