# Phase 0B Blind Corpus Topics

This topic list was fixed **before corpus item authoring**. It is derived only from generic Python standard-library coverage and common Python idioms. It was not informed by held-out tasks, model outputs, retrieval failures, or oracle mappings.

## Topic families

### Files, paths, streams, and archives
- pathlib
- os
- os.path
- shutil
- tempfile
- io
- glob
- fnmatch
- zipfile
- tarfile

### Data formats, serialization, and text
- json
- csv
- configparser
- tomllib
- pickle
- base64
- codecs
- textwrap
- string

### Dates, time, and scheduling primitives
- datetime
- time
- zoneinfo
- calendar

### Collections, iteration, and functional helpers
- collections
- itertools
- functools
- heapq
- bisect
- operator

### Matching, parsing, and structured text
- re
- urllib.parse
- html
- xml.etree.ElementTree

### Databases and persistence
- sqlite3
- dbm

### Processes, concurrency, and synchronization
- subprocess
- threading
- queue
- concurrent.futures
- multiprocessing

### Context, resource, and lifecycle management
- contextlib
- weakref
- atexit

### Logging, diagnostics, and inspection
- logging
- warnings
- traceback
- inspect
- pprint

### Data modeling and typing
- dataclasses
- enum
- typing
- types

### Hashing, identifiers, randomness, and security helpers
- hashlib
- hmac
- secrets
- uuid
- random

### Mathematics and statistics
- math
- statistics
- decimal
- fractions

### Networking and protocol helpers
- socket
- ipaddress
- email
- http

### Testing and comparison idioms
- unittest
- unittest.mock
- difflib

### Import, packaging, and runtime metadata
- importlib
- importlib.resources
- sys
- platform

### Common language idioms
- comprehensions
- generator expressions
- unpacking
- sorting with key functions
- sentinel values
- EAFP exception handling
- custom exceptions
- iterators and generators
- decorators
- context managers
- immutable records
- defensive copying
- stable deduplication
- chunking sequences
- grouping records
- retry loops with bounded attempts

## Mix target

Approximately 200 items total:
- ~80 `api_fact`
- ~70 `pattern`
- ~30 `procedure`
- ~20 `repair`

Breadth is preferred over depth. No topic family should dominate the corpus, and no single module should accumulate a large cluster of near-duplicate items.

## Freeze rule

After `corpus.jsonl` is frozen and hashed:
- no hand-picked item additions,
- no edits based on held-out tasks,
- no edits based on model failures,
- no edits based on retrieval misses,
- no oracle mapping until after freeze.
