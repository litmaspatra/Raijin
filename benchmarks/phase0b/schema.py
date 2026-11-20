from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any


class KnowledgeDependency(str, Enum):
    REASONING_ONLY = "reasoning-only"
    API_KNOWLEDGE = "api-knowledge"
    API_KNOWLEDGE_PLUS_ADAPTATION = "api-knowledge-plus-adaptation"
    ALGORITHMIC_REASONING = "algorithmic-reasoning"


@dataclass(frozen=True)
class HeldOutTask:
    task_id: str
    language: str
    instruction: str
    prefix: str
    suffix: str
    test_code: str
    knowledge_dependency: KnowledgeDependency

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "HeldOutTask":
        required = {
            "task_id",
            "language",
            "instruction",
            "prefix",
            "suffix",
            "test_code",
            "knowledge_dependency",
        }
        missing = sorted(required - raw.keys())
        if missing:
            raise ValueError(f"missing task fields: {', '.join(missing)}")

        forbidden = {"oracle_reference_ids", "oracle_item_ids"}
        present_forbidden = sorted(forbidden & raw.keys())
        if present_forbidden:
            raise ValueError(
                "held-out tasks must not carry oracle mappings before the corpus is frozen: "
                + ", ".join(present_forbidden)
            )

        task = HeldOutTask(
            task_id=str(raw["task_id"]).strip(),
            language=str(raw["language"]).strip(),
            instruction=str(raw["instruction"]).strip(),
            prefix=str(raw["prefix"]),
            suffix=str(raw["suffix"]),
            test_code=str(raw["test_code"]),
            knowledge_dependency=KnowledgeDependency(raw["knowledge_dependency"]),
        )
        task.validate()
        return task

    def validate(self) -> None:
        if not self.task_id:
            raise ValueError("task_id must not be blank")
        if not self.language:
            raise ValueError("language must not be blank")
        if not self.instruction:
            raise ValueError("instruction must not be blank")
        if "<RAIJIN_HOLE>" in self.prefix or "<RAIJIN_HOLE>" in self.suffix:
            raise ValueError("prefix/suffix must not contain the hole marker")
        if not self.test_code.strip():
            raise ValueError("test_code must not be blank")


@dataclass(frozen=True)
class ReferenceSolution:
    task_id: str
    slot_code: str

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "ReferenceSolution":
        required = {"task_id", "slot_code"}
        missing = sorted(required - raw.keys())
        if missing:
            raise ValueError(f"missing reference-solution fields: {', '.join(missing)}")
        result = ReferenceSolution(
            task_id=str(raw["task_id"]).strip(),
            slot_code=str(raw["slot_code"]),
        )
        if not result.task_id:
            raise ValueError("reference solution task_id must not be blank")
        if not result.slot_code.strip():
            raise ValueError("reference solution slot_code must not be blank")
        return result


@dataclass(frozen=True)
class OracleMapping:
    task_id: str
    item_ids: tuple[str, ...]

    @property
    def covered(self) -> bool:
        return bool(self.item_ids)


class CodeKnowledgeKind(str, Enum):
    API_FACT = "api_fact"
    PATTERN = "pattern"
    PROCEDURE = "procedure"
    TEMPLATE = "template"
    REPAIR = "repair"


@dataclass(frozen=True)
class KnowledgeItem:
    item_id: str
    kind: CodeKnowledgeKind
    language: str
    title: str
    content: str
    verified: bool

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "KnowledgeItem":
        required = {"item_id", "kind", "language", "title", "content", "verified"}
        missing = sorted(required - raw.keys())
        if missing:
            raise ValueError(f"missing knowledge fields: {', '.join(missing)}")

        item = KnowledgeItem(
            item_id=str(raw["item_id"]).strip(),
            kind=CodeKnowledgeKind(raw["kind"]),
            language=str(raw["language"]).strip(),
            title=str(raw["title"]).strip(),
            content=str(raw["content"]),
            verified=bool(raw["verified"]),
        )
        item.validate()
        return item

    def validate(self) -> None:
        if not self.item_id:
            raise ValueError("item_id must not be blank")
        if not self.language:
            raise ValueError("language must not be blank")
        if not self.title:
            raise ValueError("title must not be blank")
        if not self.content.strip():
            raise ValueError("content must not be blank")
