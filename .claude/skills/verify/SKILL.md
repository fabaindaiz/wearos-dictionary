---
name: verify
description: Corre el gate de este proyecto y reporta con honestidad qué pasó y qué no. Usar antes de commitear, después de tocar normalización o el formato de pack, y cuando se pida "verificá", "corré el gate", "chequeá", "¿está listo?" o "¿esto funciona?".
allowed-tools: Bash, Read
---

# Verify

Este repo tiene contratos que se rompen sin producir ningún error: el síntoma es una palabra que
falta, meses después. El gate es lo único que los detecta antes de que eso pase.

## El gate

```bash
./gradlew check
```

Corre compilación, Android Lint, los 40 tests de `:dict-core`, los 35 del builder Python y la
auditoría estructural. **Medido: ~1m26s en frío, ~40s templado.**

## Si tocaste `norm()`, `fuzzy()` o el repertorio Unicode

El gate ya corre los vectores compartidos, pero un pack construido antes del cambio quedó
indexado con las reglas viejas:

```bash
python3 tools/packbuilder/build_toy.py
python3 tools/packbuilder/verify_pack.py dict-data/src/androidTest/assets/toy-es-en.db
```

Si `NORM_VERSION` no subió y las claves cambiaron, **los tests pasan y el bug queda**. Es el
único caso donde el gate no alcanza.

## Si tocaste el formato del pack

Además de lo anterior, `verify_pack.py` sobre cualquier pack real que haya. Mirá específicamente
la sección `[planes de consulta]`: si el prefijo deja de usar `COVERING INDEX`, la búsqueda
incremental deja de cumplir su presupuesto de latencia y **nada más lo notaría**.

## Reportar

Decí qué pasó y qué no, con la salida. **Nunca llames verificado a algo que no corriste.** Si
algo ya venía fallando, nombralo para que no se presente como nuevo.

Lo que el gate **no** cubre, y hay que decirlo cuando alguien pregunta si está listo:

- **Nada de esto corrió nunca en un reloj.** No hay tests instrumentados.
- Los presupuestos de latencia y tamaño de `docs/formato-pack.md` son **objetivos sin medir**.
