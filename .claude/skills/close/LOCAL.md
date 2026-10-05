---
description: Cierra una sesión de código para que la siguiente, en otra máquina y sin memoria de esta, pueda seguir — cada parte del pedido rendida, los documentos verdaderos otra vez, la entrada del changelog, las fricciones contadas, la memoria local pasada al repo, el traspaso, y después el gate y los commits. Usar cuando se pida "cierra la sesión", "cierra la sesión de código con la documentación y los commits", "documenta todo y prepara los commits", "deja todo listo para seguir desde otro agente", "cerremos por hoy" o "súbelo". No cuando se pida pausar.
---

In this repository the session log is `.claude/logs/agent-changelog.md` (its format is at its end),
the roadmap's process section is §*Proceso y herramientas*, the gate is `./gradlew check` read by its
exit code (the `verify` skill), and commits follow the `commit` skill: offered when their piece passes,
grouped by context, each green in a `git worktree`. Where a step below names something this repository
defines differently, this repository's wins.
