---
name: state-review
description: Revisión periódica de la salud del sistema de instrucciones del repo. Usar cuando se pida "revisá el estado del repo", "¿está actualizada la documentación?", "state review", o al empezar a trabajar después de un tiempo sin tocar el proyecto.
allowed-tools: Bash, Read, Grep
---

# Revisión de estado

Bootstrapear no es el final: el sistema se pudre sin un ritual. Estas seis preguntas, con
evidencia, no de memoria.

## 1. ¿Existe cada documento del mapa, y sigue siendo cierto?

```bash
python3 tools/audit_dictionary.py
```

La auditoría comprueba que el mapa resuelva. Que el **contenido** siga siendo cierto hay que
mirarlo: contrastá cada número de `docs/formato-pack.md` y `docs/architecture.md` contra el
código. **Un documento que apunta a un archivo borrado es peor que no tener documento**, y es la
decadencia más común en un repo asistido por agentes, porque borran código más rápido de lo que
releen prosa.

## 2. ¿Cada regla sigue teniendo enforcer, y alguno saltó este período?

```bash
grep -c "| —" docs/decisions.md     # decisiones que se pueden romper en silencio
```

Ese número subiendo es la señal de alarma. Una regla que perdió su enforcer volvió a rung 1 sin
que nadie lo decidiera.

## 3. ¿Qué cambió que debería haber sido una fila de decisión y no lo fue?

```bash
git log --oneline $(git log -1 --format=%H -- docs/decisions.md)..HEAD
```

Commits posteriores al último cambio de `decisions.md`. Si alguno tomó una decisión de diseño,
falta su fila.

## 4. ¿Qué del roadmap ya está cerrado?

Por construido, o **por medición**. Lo segundo es lo valioso: si un número retiró una idea, va a
*Cerrado por medición* con el número, para que siga retirada.

## 5. ¿`CLAUDE.md` pasó su presupuesto?

```bash
wc -l CLAUDE.md    # tiene que estar bajo 200
```

Si creció, **qué sección creció** es la pregunta. Una sección que crece es la señal de que se
volvió un documento y hay que moverla, dejando un puntero.

## 6. ¿Qué reglas siguen en rung 1 y se podrían promover barato?

Las de `docs/decisions.md` con `—` en *Enforced in*. En este repo, las candidatas conocidas:

- **D-025** (`glance-wear-tiles` prohibido) → un grep en la auditoría, trivial.
- **D-031** (atribución CC BY-SA visible) → ship-blocking check cuando exista la UI.
- **D-002** (packs con `BundledSQLiteDriver`) → chequeable cuando exista `:dict-data`.

## 7. ¿El método sigue siendo el que decimos seguir?

```sh
grep -h "^version:\|^digest:\|^adopted:" docs/agents/prompt-update.md
python3 tools/audit_dictionary.py | grep metodo   # silencio = el digest cuadra
```

`adapted` y `declined` **vacíos** después de un update es la señal de que el header no se está
manteniendo, y el próximo update va a re-proponer todo lo ya rechazado (D-059, D-060).

## 8. Los smells, que se chequean en un minuto

Cada uno tiene una respuesta corta; lo que importa es que ninguno se conteste de memoria.

| Smell | Cómo se mira |
|---|---|
| El roadmap no tiene ninguna entrada **Hecho** | `grep -c "Estado.*Hecho" docs/roadmap.md` — o no se terminó nada, o terminar no escribe de vuelta |
| §Proceso y herramientas **vacía** | La fricción no se está anotando. No es que no haya |
| Todas las entradas del changelog salieron perfectas | `grep -c "Qué salió mal" .claude/logs/agent-changelog.md` contra el total de entradas. Nadie trabaja así: los desvíos se están editando afuera |
| Una decisión con enforcer `— ` que **sí** se podría chequear | Pregunta 6 |
| Dos documentos afirman el mismo número | Uno ya está viejo y no se sabe cuál |
| Un límite del gate se subió junto con una feature | `git log -p -- tools/audit_dictionary.py` — el límite *era* el mensaje |
| Ninguna sesión propuso nunca una mejora de proceso | Es el modo de falla 6, y es invisible justamente porque nada se rompe |

## Y la pregunta que no está en la lista

**¿Sigue sin haber un test corriendo en un reloj?** Es la clase de bug que este repo no puede
ver, y mientras la respuesta sea "sí", cualquier afirmación sobre el comportamiento en Android
es ASSUMPTION — por bien testeado que esté en escritorio.
