#!/usr/bin/env python3
"""Build the on-device English Kokoro bundle (two voices) from pinned upstream files.

Usage: prepare_kokoro.py INT8_ONNX SHERPA_V1_0_DIR OUT_DIR

INT8_ONNX        kokoro-v1.0.int8.onnx (kokoro-onnx model-files-v1.0 release, checksum-verified by CI)
SHERPA_V1_0_DIR  extracted kokoro-multi-lang-v1_0 package from the sherpa-onnx tts-models release
OUT_DIR          receives model.int8.onnx, voices.bin, tokens.txt, lexicon-us-en.txt

Only two voices are kept: af_heart (female, sid 0) and bm_george (male, sid 1). The sherpa
voices.bin is raw float32, 510 * 256 values per voice, in the order listed in its model
metadata (af_heart is index 3, bm_george is index 26).
No espeak-ng data is used or copied: sherpa-onnx 2.x reads the lexicon only.
"""
import os
import shutil
import sys

import onnx

VOICES = [("af_heart", 3), ("bm_george", 26)]
STYLE_BYTES = 510 * 256 * 4


def main() -> None:
    int8_path, src_dir, out_dir = sys.argv[1:4]
    os.makedirs(out_dir, exist_ok=True)

    raw = open(os.path.join(src_dir, "voices.bin"), "rb").read()
    assert len(raw) == 54 * STYLE_BYTES, "unexpected voices.bin size"
    with open(os.path.join(out_dir, "voices.bin"), "wb") as f:
        for _, idx in VOICES:
            f.write(raw[idx * STYLE_BYTES:(idx + 1) * STYLE_BYTES])

    model = onnx.load(int8_path)
    meta = {
        "model_type": "kokoro",
        "language": "English",
        "has_espeak": "0",
        "sample_rate": "24000",
        "version": "2",
        "voice": "en-us",
        "style_dim": "510,1,256",
        "n_speakers": str(len(VOICES)),
        "id2speaker": ",".join(f"{i}->{n}" for i, (n, _) in enumerate(VOICES)),
        "speaker2id": ",".join(f"{n}->{i}" for i, (n, _) in enumerate(VOICES)),
        "speaker_names": ",".join(n for n, _ in VOICES),
        "maintainer": "raijin",
    }
    for k, v in meta.items():
        e = model.metadata_props.add()
        e.key, e.value = k, v
    onnx.save(model, os.path.join(out_dir, "model.int8.onnx"))

    for name in ("tokens.txt", "lexicon-us-en.txt"):
        shutil.copy(os.path.join(src_dir, name), os.path.join(out_dir, name))


if __name__ == "__main__":
    main()
