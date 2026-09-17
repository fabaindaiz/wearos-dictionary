---
name: commit
description: Prepara y verifica commits para este repo. Usar cuando el trabajo está terminado y se pida "commiteá", "hacé los commits", "guardá esto" o "subí los cambios".
allowed-tools: Bash, Read
---

# Commit

## Se parten por dependencia, no por tamaño

**Cada commit tiene que quedar verde por sí solo**, o el historial no es bisecable y no sirve
para encontrar cuándo se rompió algo.

En este repo eso impone un orden concreto: `tools/` antes que `dict-core/`, porque los tests de
Kotlin consumen los vectores compartidos que viven ahí. Al revés, el commit intermedio queda
rojo.

## Verificalo, no lo supongas

```bash
git worktree add -q --detach /tmp/wt-<ref> <ref>
cd /tmp/wt-<ref> && echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew check
git worktree remove --force /tmp/wt-<ref>
```

Un clone limpio también: es lo que detectó que `gradle-wrapper.jar` nunca había estado trackeado
y el repo no se podía buildear desde cero (D-021).

## El mensaje dice por qué

Qué archivos cambiaron ya lo dice el diff. El cuerpo explica la decisión, y si una medición mató
una creencia, ese es el contenido más valioso del commit.

## Antes de ofrecer commitear

- El gate pasa (`verify` skill).
- La entrada del changelog está escrita: `.claude/logs/agent-changelog.md`.
- Si se tomó una decisión nueva, tiene su fila en `docs/decisions.md` con la columna
  *Enforced in* llena — aunque diga `—`.
- Nada generado entra al repo: ver la lista en `CLAUDE.md`.

**No se commitea por iniciativa propia a mitad de tarea.** Se ofrece cuando el trabajo terminó.
