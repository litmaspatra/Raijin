# Phase 0B Blind Corpus Freeze

**Status:** FROZEN. Do not edit corpus items after this point.

The topic list was committed before item authoring in commit `7a0aadb2a302ab047193d6568e6853bc7cd1fc7b`.

The frozen corpus:
- path: `benchmarks/phase0b/corpus/corpus.jsonl`
- item count: 200
- SHA-256: `82002b908d0ca799a907f21687bfedd59b4fe693621298b26a13e721bf17243b`

Kind distribution:
- `api_fact`: 80
- `pattern`: 70
- `procedure`: 30
- `repair`: 20

Module/topic counts:
- atexit 1
- base64 4
- bisect 2
- calendar 1
- codecs 1
- collections 6
- concurrent.futures 2
- configparser 3
- contextlib 5
- csv 6
- dataclasses 6
- datetime 6
- decimal 4
- difflib 2
- email 2
- enum 3
- fnmatch 2
- fractions 2
- functools 6
- glob 1
- hashlib 4
- heapq 2
- hmac 3
- html 2
- importlib 2
- inspect 2
- io 3
- ipaddress 3
- itertools 6
- json 6
- language 5
- logging 4
- math 3
- operator 2
- os 2
- os.path 2
- pathlib 6
- pickle 1
- platform 1
- pprint 2
- queue 4
- random 2
- re 6
- secrets 2
- shutil 1
- socket 1
- sqlite3 6
- statistics 2
- string 2
- subprocess 6
- sys 2
- tarfile 1
- tempfile 5
- textwrap 2
- threading 1
- tomllib 3
- traceback 2
- types 1
- typing 2
- unittest.mock 4
- urllib.parse 6
- uuid 2
- warnings 2
- weakref 2
- xml.etree 3
- zipfile 3
- zoneinfo 1

Verification:
- workflow: `Phase 0B Freeze Corpus`
- run ID: `37562649314`
- all 200 `verified=true` snippets executed inside the Docker sandbox
- verification and corpus validation passed
- execution record artifact: `phase0b-frozen-corpus-verification` (artifact ID `11456444737`)

The manifest now records the corpus SHA-256.

## Freeze rule

From this point onward:
- do not add, remove, or edit corpus items,
- do not tune corpus content after seeing held-out tasks,
- do not tune corpus content after model or retrieval failures,
- do not create oracle mappings until the next approved step,
- do not build retrieval conditions or the model runner yet.
