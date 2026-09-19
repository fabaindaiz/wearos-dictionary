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

Corre compilación, Android Lint, los **47 de `:dict-core`**, los **85 JVM de `:app`**, los
**101 del builder Python** y la auditoría estructural (**18 checks**).
**Medido: ~1m26s en frío, ~40s templado.**

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

## Los tests en dispositivo, que el gate no corre

```bash
./gradlew :dict-data:devicePrecheck             # ¿hay con qué? Dice qué falta si no
./gradlew :dict-data:connectedDebugAndroidTest  # los 31 tests
./gradlew :app:connectedDebugAndroidTest       # los 47 de pantalla
```

Necesitan un emulador o un reloj conectado, por eso están fuera del gate. `devicePrecheck`
existe porque sin dispositivo Gradle falla con un error que no dice qué hacer. Son los únicos que
cierran las asunciones sobre Android: que el SQLite empacado traiga FTS5, que el prefijo use el
covering index en ese dispositivo, y sobre todo que `norm()` dé lo mismo en el reloj que en el
builder.

Corrélos en **cada nivel de API soportado**. Correr uno solo no prueba lo que el test intenta
probar, que es justamente que las versiones difieren.

Lo que el gate **no** cubre, y hay que decirlo cuando alguien pregunta si está listo:

- **Los tests instrumentados existen pero pueden no haberse corrido nunca.** Compilan en el
  gate; ejecutarse, no. Chequeá antes de afirmar que algo funciona en Android.
- Los presupuestos de latencia y tamaño de `docs/formato-pack.md` son **objetivos sin medir**.
