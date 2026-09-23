"""Builds the toy pack :dict-data's tests consume.

    python3 build_toy.py [output.db]

By default it writes into :dict-data's assets directory. The pack is deterministic: two runs over
the same data give the same content, so it does not dirty the diff when nothing changed (except
meta.built_at).
"""

import os
import sys

from sources import toy

from build import PackBuilder

DEFAULT_OUTPUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "dict-data", "src", "androidTest", "assets", "toy-es-en.db",
)


def main(argv):
    output = argv[1] if len(argv) > 1 else DEFAULT_OUTPUT
    os.makedirs(os.path.dirname(output), exist_ok=True)

    with PackBuilder(output, toy.METADATA) as builder:
        for record in toy.records():
            builder.add(record)

    print("%s: %d entradas, %d bytes" % (output, builder.count, os.path.getsize(output)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
