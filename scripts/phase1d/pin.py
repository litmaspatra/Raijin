#!/usr/bin/env python3
"""Hash an existing file for a pinned manifest. No network or downloads."""
import argparse
import hashlib
import json
import pathlib
import re

def pin(path, source_revision, published_sha256=None, accept_local_hash=False,
        package_version=None, provenance=None, parameters_billion=None, quantization=None):
    if bool(published_sha256) == bool(accept_local_hash):
        raise ValueError("Select either --published-sha256 or --accept-local-hash (not both)")
    if not source_revision.strip() or "REPLACE" in source_revision:
        raise ValueError("Exact source revision/release required")
    if accept_local_hash and (not package_version or not provenance):
        raise ValueError("Local hash mode requires package version AND provenance")
    if published_sha256 and not re.fullmatch(r"[0-9a-fA-F]{64}", published_sha256):
        raise ValueError("Published SHA-256 must be 64 hex characters")
    path = pathlib.Path(path).expanduser().resolve(strict=True)
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1048576), b""):
            digest.update(block)
    actual = digest.hexdigest()
    if published_sha256 and actual != published_sha256.lower():
        raise ValueError("Local file differs from published SHA-256")
    result = {"path":str(path), "sha256":actual, "source_revision":source_revision,
              "hash_verification":"upstream-published" if published_sha256 else "local-only"}
    if accept_local_hash:
        result.update({"package_version":package_version, "provenance":provenance,
                       "warning":"Local checksum pins these bytes; does NOT verify against upstream."})
    if parameters_billion is not None:
        if not 0 < parameters_billion <= 2: raise ValueError("Only <=2B candidates permitted")
        result["parameters_billion"] = parameters_billion
    if quantization is not None: result["quantization"] = quantization
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file")
    parser.add_argument("--source-revision",required=True)
    group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--published-sha256")
    group.add_argument("--accept-local-hash",action="store_true")
    parser.add_argument("--package-version")
    parser.add_argument("--provenance")
    parser.add_argument("--parameters-billion",type=float)
    parser.add_argument("--quantization")
    args=parser.parse_args()
    result=pin(args.file,args.source_revision,args.published_sha256,args.accept_local_hash,
               args.package_version,args.provenance,args.parameters_billion,args.quantization)
    print(json.dumps(result,indent=2))

if __name__=="__main__":
    main()
