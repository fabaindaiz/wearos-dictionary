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

## Y la pregunta que no está en la lista

**¿Sigue sin haber un test corriendo en un reloj?** Es la clase de bug que este repo no puede
ver, y mientras la respuesta sea "sí", cualquier afirmación sobre el comportamiento en Android
es ASSUMPTION — por bien testeado que esté en escritorio.
