"""Construye el pack de juguete que consumen los tests de :dict-data.

    python3 build_toy.py [salida.db]

Por defecto escribe en el directorio de assets de :dict-data. El pack es determinista: dos
corridas sobre los mismos datos dan el mismo contenido, asi que no ensucia el diff si no cambio
nada (salvo meta.built_at).
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
