---
description: Revisa un spec, un diseño o un plan antes de construir nada — lista todas sus decisiones, y cada una que es del dueño se la pone en su propio turno, con la recomendación primero, cada opción mostrada con un caso concreto y su precio en las unidades de este repo (MB, ms, filas, líneas por pantalla) y lo que cierra — hasta que no quede ninguna, y registra las respuestas. Usar cuando se pida "revisemos las decisiones", "hazme todas las preguntas", "explícame las cosas sin decidir y ayúdame a decidirlas", "¿qué falta por decidir?", "consúltamelo antes de tomarlo", "define bien las tareas y sus consecuencias", o antes de planear algo cuyas decisiones no están cerradas. No para las preguntas de arranque de una tarea, y no mientras se ejecuta un plan.
---

In this repository a decision taken is a row in `docs/decisions.md` with an id from
`bundle.py id d` and its *Enforced in* column filled; a question left open goes to the roadmap item
it blocks. An option the owner picks against the recommendation is carried out whole and its cost
recorded as ⚠️ Desviación, not re-argued. Where a step below names something this repository defines
differently, this repository's wins.
