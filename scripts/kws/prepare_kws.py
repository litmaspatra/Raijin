#!/usr/bin/env python3
"""Copy the int8 keyword-spotter files and write pieces.txt (piece<TAB>score) from bpe.model.

Usage: prepare_kws.py <extracted model dir> <output dir>
"""
import glob
import os
import shutil
import subprocess
import sys

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)


def pick(pattern):
    hits = sorted(glob.glob(os.path.join(src, pattern)))
    if not hits:
        sys.exit(f"missing {pattern} in {src}")
    return hits[0]


shutil.copy(pick("encoder-*chunk-16-left-64.int8.onnx"), os.path.join(out, "encoder.int8.onnx"))
shutil.copy(pick("decoder-*chunk-16-left-64.int8.onnx"), os.path.join(out, "decoder.int8.onnx"))
shutil.copy(pick("joiner-*chunk-16-left-64.int8.onnx"), os.path.join(out, "joiner.int8.onnx"))
shutil.copy(os.path.join(src, "tokens.txt"), os.path.join(out, "tokens.txt"))

subprocess.check_call([sys.executable, "-m", "pip", "install", "--quiet", "sentencepiece==0.2.0"])
import sentencepiece as spm  # noqa: E402

sp = spm.SentencePieceProcessor(model_file=os.path.join(src, "bpe.model"))
with open(os.path.join(out, "pieces.txt"), "w", encoding="utf-8") as f:
    for i in range(sp.get_piece_size()):
        if sp.is_unknown(i) or sp.is_control(i):
            continue
        f.write(f"{sp.id_to_piece(i)}\t{sp.get_score(i)}\n")
