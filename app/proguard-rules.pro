# Reglas de R8 para el release. Deliberadamente CORTAS.
#
# El release compila y encoge sin ninguna regla: el dex pasa de 29,5 a 2,7 MB y sobreviven las
# clases que hacen falta. Lo que hay aca no es para que funcione, es para que **siga**
# funcionando cuando alguien toque algo -- y por eso cada regla dice de que falla protege.

# Los dos TileService estan nombrados en AndroidManifest.xml y ademas se pasan como literal de
# clase a `TileUpdater.requestUpdate`. AGP ya conserva los componentes del manifest, asi que esto
# es cinturon y tiradores; existe porque `app/CLAUDE.md` tiene escrito que romperlos **no da
# error de compilacion ni test**, y esa propiedad no deberia depender de un detalle de AGP.
-keep class cl.fadiaz.dictionary.tile.HistoryTileService { *; }
-keep class cl.fadiaz.dictionary.tile.WordOfTheDayTileService { *; }

# El driver de SQLite carga una libreria nativa y cruza JNI. Los metodos nativos y sus clases no
# pueden renombrarse: del otro lado el nombre esta compilado en el .so.
-keep class androidx.sqlite.driver.bundled.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
