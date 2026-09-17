-- Indices de un pack, en un archivo aparte a proposito.
--
-- build.py corre schema.sql al empezar y ESTE archivo al terminar: construir los indices sobre
-- las tablas ya pobladas es mucho mas rapido que mantenerlos fila por fila durante la ingesta.
--
-- Estan separados en dos archivos y no extraidos con un split por ';', porque un comentario que
-- contenga un punto y coma rompe cualquier parser de SQL hecho a mano (paso exactamente eso).

-- Indice de cobertura de la busqueda por prefijo, que es el 95% del uso. Tiene las cuatro
-- columnas que la lista de resultados necesita, asi que SQLite responde sin tocar la tabla y
-- sin leer un solo payload. `id` no se incluye: al ser alias de rowid ya esta en todo indice.
-- El orden (norm, rank) satisface el ORDER BY de la consulta sin paso de sort. La direccion
-- de `rank` no es un detalle: es ASCENDENTE porque en `rank` menor es mas comun (schema.sql).
-- Estuvo en DESC hasta schema_version 3, y el sintoma solo se ve con un pack real: "escrit"
-- devolvia "escrito / Participio de escribir" antes que el sustantivo. Si el indice y el
-- ORDER BY se separan, SQLite agrega USE TEMP B-TREE y la consulta deja de ser de cobertura.
CREATE INDEX idx_entry_norm ON entry (norm, rank, headword, pos);

-- Indice del nivel tolerante a errores. Deliberadamente angosto: incluye `norm` para poder
-- reordenar los candidatos por distancia de edicion sin leer la tabla, y despues se leen de la
-- tabla solo los diez que sobrevivieron. Agregar headword/pos aca lo haria de cobertura pero
-- duplicaria varios MB por un camino que solo se recorre cuando el prefijo no dio resultados.
CREATE INDEX idx_entry_fuzzy ON entry (fuzzy, norm);
