import os
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from harness import OutputFormat, run_python_task
from schema import HeldOutTask, KnowledgeDependency


RUN_DOCKER = os.environ.get("RAIJIN_DOCKER_INTEGRATION") == "1"


def task(task_id: str, instruction: str, prefix: str, test_code: str) -> HeldOutTask:
    return HeldOutTask(
        task_id=task_id,
        language="python",
        instruction=instruction,
        prefix=prefix,
        suffix="\n",
        test_code=test_code,
        knowledge_dependency=KnowledgeDependency.REASONING_ONLY,
    )


@unittest.skipUnless(RUN_DOCKER, "set RAIJIN_DOCKER_INTEGRATION=1 to run real Docker tests")
class DockerIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run(["docker", "version"], check=True, capture_output=True, text=True)

    def test_correct_slot_passes_and_wrong_slot_fails(self):
        add_task = task(
            "docker-add",
            "Return a+b.",
            "def add(a, b):\n    ",
            "from candidate import add\nassert add(2, 3) == 5\n",
        )
        correct = run_python_task(
            add_task,
            "return a + b",
            output_format=OutputFormat.CHAT_BLOCK,
        )
        wrong = run_python_task(
            add_task,
            "return a - b",
            output_format=OutputFormat.CHAT_BLOCK,
        )
        self.assertTrue(correct.passed, correct.stderr)
        self.assertFalse(wrong.passed)

    def test_infinite_loop_times_out(self):
        loop_task = task(
            "docker-loop",
            "Loop forever.",
            "def loop_forever():\n    ",
            "from candidate import loop_forever\nloop_forever()\n",
        )
        result = run_python_task(
            loop_task,
            "while True:\n    pass",
            output_format=OutputFormat.CHAT_BLOCK,
            inner_timeout_seconds=1,
            outer_timeout_seconds=10,
        )
        self.assertFalse(result.passed)
        self.assertTrue(result.timed_out, result.stderr)

    def test_network_is_blocked(self):
        network_task = task(
            "docker-network",
            "Return True when outbound network is unavailable.",
            "def network_blocked():\n    ",
            "from candidate import network_blocked\nassert network_blocked() is True\n",
        )
        slot = (
            "import socket\n"
            "try:\n"
            "    sock = socket.create_connection(('1.1.1.1', 53), timeout=0.5)\n"
            "except OSError:\n"
            "    return True\n"
            "else:\n"
            "    sock.close()\n"
            "    return False"
        )
        result = run_python_task(
            network_task,
            slot,
            output_format=OutputFormat.CHAT_BLOCK,
        )
        self.assertTrue(result.passed, result.stderr)


if __name__ == "__main__":
    unittest.main()
