-- Esquema de un pack de diccionario. schema_version = 3
--
-- El pack es inmutable y se abre siempre en modo read-only, asi que no hay migraciones: un
-- pack con schema_version distinta se rechaza al abrirlo y se descarga de nuevo. Eso permite
-- optimizar el esquema unicamente para lectura.
--
-- Los indices se crean DESPUES de insertar todo (ver build.py): construirlos sobre la tabla ya
-- poblada es mucho mas rapido que mantenerlos fila por fila.

PRAGMA page_size = 4096;
PRAGMA encoding = 'UTF-8';

-- Todo lo que la app necesita saber del pack antes de consultarlo. Se lee completa, una vez,
-- al abrir. Claves obligatorias en verify_pack.py: REQUIRED_META.
CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;

-- Dos identidades por entrada, y no son intercambiables (D-055):
--
--   id  es la identidad FISICA. Alias de rowid: lo comparte fts_def y lo referencian form y
--       trans. Es secuencial porque eso es lo que lo hace barato -- FTS5 guarda deltas de
--       rowid-- y NO sobrevive a reconstruir el pack: una palabra nueva en el medio corre
--       todos los ids siguientes.
--   uid es la identidad LOGICA. Sobrevive al rebuild y es por donde un pack auxiliar
--       (sinonimos, traducciones) le suma informacion a esta misma entrada. Lo calcula solo
--       el builder (ver stable_uid() en build.py); la app lo lee, nunca lo recalcula.
--
-- uid NO lleva indice en este pack a proposito: el join ocurre al ABRIR una entrada, cuando la
-- fila ya se leyo entera, no en la lista de resultados --que la sirve el covering index sin
-- tocar la tabla (D-012)--. El indice sobre uid vive en el pack auxiliar, que si busca por el.
CREATE TABLE entry (
    id       INTEGER PRIMARY KEY,   -- alias de rowid: lo comparte fts_def
    uid      INTEGER NOT NULL,      -- identidad estable entre rebuilds; clave de join entre packs
    headword TEXT NOT NULL,         -- forma de display, con acentos y mayusculas: "Ärztin"
    norm     TEXT NOT NULL,         -- clave de busqueda por prefijo: "arztin"
    fuzzy    TEXT NOT NULL,         -- clave tolerante a errores, plegada por idioma
    pos      TEXT,                  -- part of speech; desambigua lemas repetidos en la lista
    rank     INTEGER NOT NULL,      -- frecuencia de uso; menor es mas comun. Ordena resultados
    payload  BLOB NOT NULL          -- cuerpo comprimido (ver payload.py / PayloadCodec.kt)
);

-- Formas flexionadas -> lema: plurales, conjugaciones, variantes ortograficas.
-- WITHOUT ROWID con la PK compuesta hace que la tabla SEA el indice: sin rowid y sin un
-- B-tree secundario que duplique los mismos datos.
CREATE TABLE form (
    norm     TEXT NOT NULL,
    entry_id INTEGER NOT NULL,
    PRIMARY KEY (norm, entry_id)
) WITHOUT ROWID;

-- Palabra del idioma destino -> entrada. En un pack bilingue es la busqueda inversa; en uno
-- monolingue son las palabras que aparecen en la glosa.
CREATE TABLE trans (
    norm     TEXT NOT NULL,
    entry_id INTEGER NOT NULL,
    PRIMARY KEY (norm, entry_id)
) WITHOUT ROWID;

-- Texto libre de las definiciones. Contentless (content=''): guarda solo el indice invertido y
-- no una segunda copia del texto, que ya vive comprimido en entry.payload. Solo devuelve
-- rowids, que es exactamente lo que se necesita porque rowid == entry.id.
--
-- Consecuencia asumida: sin snippet() ni highlight(). El resaltado se hace en Kotlin sobre el
-- payload descomprimido de los pocos resultados que se muestran.
--
-- remove_diacritics 2 es la version que pliega diacriticos sobre todo el rango Unicode, no
-- solo Latin-1.
CREATE VIRTUAL TABLE fts_def USING fts5(
    body,
    content = '',
    tokenize = "unicode61 remove_diacritics 2"
);
