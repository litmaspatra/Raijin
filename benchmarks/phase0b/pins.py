from __future__ import annotations

PYTHON_SANDBOX_IMAGE = "python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f"
PYTHON_SANDBOX_DIGEST = "sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f"

MINILM_REVISION = "10dbd2f06a8baf40ad285b037edda610c1b9a57c"
MINILM_ONNX_URL = (
    "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/"
    + MINILM_REVISION
    + "/onnx/model.onnx"
)
MINILM_ONNX_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"
MINILM_TOKENIZER_URL = (
    "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/"
    + MINILM_REVISION
    + "/tokenizer.json"
)
MINILM_TOKENIZER_SHA256 = "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"

QWEN_TOKENIZER_REVISION = "8123ea2e9354afb7ffcc6c8641d1b2f5ecf18301"
QWEN_TOKENIZER_URL = (
    "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B/resolve/"
    + QWEN_TOKENIZER_REVISION
    + "/tokenizer.json"
)
QWEN_TOKENIZER_SHA256 = "c0382117ea329cdf097041132f6d735924b697924d6f6fc3945713e96ce87539"

RRF_K = 60
TOP_K = 3
CONTEXT_BUDGET_TOKENS = 400
