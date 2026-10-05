---
when_to_use: Usar cuando se pida "piensa cómo lo usaría alguien", "qué pasa si se corta el wifi o se cierra la app a la mitad", "prueba todas las formas de cierre", "casos borde", o cuando el dueño trae observaciones de usar la app en el reloj ("la app se instaló pero tiene algunas observaciones"). No para revisar decisiones (decision-review), y no reemplaza probar con personas reales.
---

In this repository a walk that needs a screen runs on the emulator that reproduces the device's
geometry (`tools/avd_como_el_reloj.py`; its startup log must say it is the device's), never with taps
or screenshots on the real device, whose probe is install, launch and read the log. A behaviour the
gate cannot see (colour, animation, where a line breaks) is measured on that emulator and said so in
the test. Where a step below names something this repository defines differently, this repository's
wins.
