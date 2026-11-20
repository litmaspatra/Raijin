import hashlib
import json
import pathlib
import tempfile
import unittest
from measure import require_pinned, sample_device, parse_stt_events, run_command, main
from pin import pin

class HarnessTests(unittest.TestCase):
    def test_pin_local_hash_requires_provenance_and_package_version(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "llama-bench"
            p.write_bytes(b"installed binary")
            with self.assertRaises(ValueError):
                pin(p, "pkg-v1", accept_local_hash=True)
            with self.assertRaises(ValueError):
                pin(p, "pkg-v1", accept_local_hash=True, package_version="v1")
            entry = pin(p, "pkg-v1", accept_local_hash=True,
                        package_version="v1", provenance="Termux dpkg-query")
            self.assertEqual(entry["hash_verification"], "local-only")
            self.assertEqual(entry["package_version"], "v1")
            self.assertEqual(entry["sha256"], hashlib.sha256(p.read_bytes()).hexdigest())

    def test_pin_published_checksum_verification(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "model.gguf"
            p.write_bytes(b"test")
            digest = hashlib.sha256(p.read_bytes()).hexdigest()
            entry = pin(p, "sha-123", published_sha256=digest)
            self.assertEqual(entry["hash_verification"], "upstream-published")
            with self.assertRaises(ValueError):
                pin(p, "sha-123", published_sha256="0" * 64)
            with self.assertRaises(ValueError):
                pin(p, "sha-123", published_sha256=digest, accept_local_hash=True)

    def test_invalid_hash_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)/"asset"
            p.write_bytes(b"test")
            with self.assertRaises(ValueError):
                require_pinned({"path":str(p),"sha256":"0"*64})

    def test_device_snapshot_has_nullable_sensors(self):
        snapshot = sample_device()
        self.assertIn("battery_percent", snapshot)
        self.assertIsNone(snapshot["power_mode"])
        json.dumps(snapshot)

    def test_parses_stt_events(self):
        events = parse_stt_events('RAIJIN_STT_EVENT {"kind":"ready","elapsed_ms":100}\n'
            'RAIJIN_STT_EVENT {"kind":"partial","elapsed_ms":140,"latency_ms":40}\n')
        self.assertEqual(events["startup_ms"],100)
        self.assertEqual(events["first_partial_ms"],140)
        self.assertEqual(events["streaming_latency_ms"],[40])

    def test_run_command_timeout_kills_process(self):
        with tempfile.TemporaryDirectory() as d:
            r = run_command("stuck",["python3","-c","import time;time.sleep(5)"],pathlib.Path(d),timeout=.01)
            self.assertTrue(r["timed_out"])
            self.assertNotEqual(r["exit_code"],0)

    def test_validation_and_end_to_end_optional_stt(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            bench=root/"fake-bench"
            bench.write_text('#!/usr/bin/env python3\n'
                'import json\n'
                'print(json.dumps([{"n_prompt":512,"n_gen":0,"avg_ts":45.0,"backend":"CPU","n_gpu_layers":0,"n_threads":4,"build_info":"fake-b1"},'
                '{"n_prompt":0,"n_gen":128,"avg_ts":12.0,"backend":"CPU","n_gpu_layers":0,"n_threads":4,"build_info":"fake-b1"}]))\n')
            bench.chmod(0o755)
            stt=root/"fake-stt"
            stt.write_text('#!/usr/bin/env python3\n'
                'print(\'RAIJIN_STT_EVENT {"kind":"ready","elapsed_ms":7}\')\n'
                'print(\'RAIJIN_STT_EVENT {"kind":"partial","elapsed_ms":12,"latency_ms":5}\')\n')
            stt.chmod(0o755)
            model=root/"fake-model.Q4_K_M.gguf"
            model.write_bytes(b"fake-model-not-a-real-gguf")
            audio=root/"fixed.wav";audio.write_bytes(b"FAKE AUDIO")
            def pin(path):
                return {"path":str(path),"sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                        "source_revision":"fixed-test-commit"}
            manifest={"schema_version":1,"llama_bench":pin(bench),
                "models":[{**pin(model),"parameters_billion":1.5,"quantization":"Q4_K_M"}],
                "stt":None,"protocol":{"threads":4,"prefill_tokens":512,
                                        "generate_tokens":128,"thermal_seconds":0}}
            config=root/"manifest.json"
            def write():config.write_text(json.dumps(manifest))
            write()
            manifest["models"][0]["parameters_billion"]=2.5;write()
            with self.assertRaises(ValueError):main(config,root/"reject-large")
            manifest["models"][0]["parameters_billion"]=1.5
            manifest["models"][0]["quantization"]="Q8_0";write()
            with self.assertRaises(ValueError):main(config,root/"reject-q8")
            manifest["models"][0]["quantization"]="Q4_K_M"
            manifest["models"][0]["source_revision"]="REPLACE";write()
            with self.assertRaises(ValueError):main(config,root/"reject-placeholder")
            manifest["models"][0]["source_revision"]="fixed-test-commit";write()
            main(config,root/"llm-only")
            data=json.loads((root/"llm-only"/"results.json").read_text())
            self.assertIsNone(data["stt"])
            self.assertEqual(data["models"][0]["trials"][0]["prefill_tok_per_s"],45.0)
            self.assertEqual(data["models"][0]["trials"][0]["generation_tok_per_s"],12.0)
            self.assertEqual(data["models"][0]["trials"][0]["ngl"],0)
            self.assertEqual(data["models"][0]["trials"][0]["build_info"],"fake-b1")
            self.assertEqual(data["schema_version"],1)
            self.assertEqual(len(data["models"][0]["trials"]),2)
            manifest["stt"]={**pin(stt),"args":["{audio}"],"assets":[],
                             "audio_file":str(audio),"audio_sha256":pin(audio)["sha256"]}
            write()
            main(config,root/"with-stt")
            data=json.loads((root/"with-stt"/"results.json").read_text())
            self.assertEqual(data["stt"]["startup_ms"],7)
            self.assertEqual(data["stt"]["streaming_latency_ms"],[5])

if __name__=="__main__":
    unittest.main()
