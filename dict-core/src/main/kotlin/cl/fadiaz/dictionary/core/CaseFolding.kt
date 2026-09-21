package cl.fadiaz.dictionary.core

// ARCHIVO GENERADO por tools/unicode/gen_casefold.py -- NO EDITAR A MANO.
// Fuente de verdad: tools/unicode/casefold.txt
//
// Case folding: la operacion que el estandar define para *caseless matching* --regla R4,
// seccion 3.13 del Estandar Unicode-- y que NO es `lowercase()`. El estandar lo separa
// explicitamente: case mapping sirve para MOSTRAR texto, case folding para COMPARARLO.
//
// ⚠️ Es una TABLA y no una llamada a la plataforma porque Java y Kotlin **no tienen**
// `toCaseFold()`: lo unico que lo ofrece es ICU, y D-003 prohibe los datos Unicode de la
// plataforma porque cada Android trae su version --14.773 code points se clasificaban distinto
// entre relojes. Fijar la tabla es el mismo patron que UnicodeRepertoire, por el mismo motivo.
//
// Solo lleva los 297 code points donde casefold() difiere de lower(); el resto lo dan las dos
// plataformas identico y esta medido (D-004).
//
// Fijado en Unicode 13.0.0. Regenerar invalida todos los sense_code ya escritos.
internal object CaseFolding {

    const val UNICODE_VERSION: String = "13.0.0"

    /** sha256 del blob codificado. Ata esta copia a tools/unicode/casefold.txt. */
    const val DIGEST: String = "a2c635c5e3f847057ef92a238533e4f60c8badc3240ad0d974ac06526c621e6e"

    const val PAIR_COUNT: Int = 297

    /** `cp:plegado` en hexadecimal, separados por comas. */
    internal const val ENCODED: String =
        "b5:03bc,df:00730073,149:02bc006e,17f:0073,1f0:006a030c,345:03b9,390:03b903080301,3b0:03c503080301,3c" +
        "2:03c3,3d0:03b2,3d1:03b8,3d5:03c6,3d6:03c0,3f0:03ba,3f1:03c1,3f5:03b5,587:05650582,13a0:13a0,13a1:13" +
        "a1,13a2:13a2,13a3:13a3,13a4:13a4,13a5:13a5,13a6:13a6,13a7:13a7,13a8:13a8,13a9:13a9,13aa:13aa,13ab:13" +
        "ab,13ac:13ac,13ad:13ad,13ae:13ae,13af:13af,13b0:13b0,13b1:13b1,13b2:13b2,13b3:13b3,13b4:13b4,13b5:13" +
        "b5,13b6:13b6,13b7:13b7,13b8:13b8,13b9:13b9,13ba:13ba,13bb:13bb,13bc:13bc,13bd:13bd,13be:13be,13bf:13" +
        "bf,13c0:13c0,13c1:13c1,13c2:13c2,13c3:13c3,13c4:13c4,13c5:13c5,13c6:13c6,13c7:13c7,13c8:13c8,13c9:13" +
        "c9,13ca:13ca,13cb:13cb,13cc:13cc,13cd:13cd,13ce:13ce,13cf:13cf,13d0:13d0,13d1:13d1,13d2:13d2,13d3:13" +
        "d3,13d4:13d4,13d5:13d5,13d6:13d6,13d7:13d7,13d8:13d8,13d9:13d9,13da:13da,13db:13db,13dc:13dc,13dd:13" +
        "dd,13de:13de,13df:13df,13e0:13e0,13e1:13e1,13e2:13e2,13e3:13e3,13e4:13e4,13e5:13e5,13e6:13e6,13e7:13" +
        "e7,13e8:13e8,13e9:13e9,13ea:13ea,13eb:13eb,13ec:13ec,13ed:13ed,13ee:13ee,13ef:13ef,13f0:13f0,13f1:13" +
        "f1,13f2:13f2,13f3:13f3,13f4:13f4,13f5:13f5,13f8:13f0,13f9:13f1,13fa:13f2,13fb:13f3,13fc:13f4,13fd:13" +
        "f5,1c80:0432,1c81:0434,1c82:043e,1c83:0441,1c84:0442,1c85:0442,1c86:044a,1c87:0463,1c88:a64b,1e96:00" +
        "680331,1e97:00740308,1e98:0077030a,1e99:0079030a,1e9a:006102be,1e9b:1e61,1e9e:00730073,1f50:03c50313" +
        ",1f52:03c503130300,1f54:03c503130301,1f56:03c503130342,1f80:1f0003b9,1f81:1f0103b9,1f82:1f0203b9,1f8" +
        "3:1f0303b9,1f84:1f0403b9,1f85:1f0503b9,1f86:1f0603b9,1f87:1f0703b9,1f88:1f0003b9,1f89:1f0103b9,1f8a:" +
        "1f0203b9,1f8b:1f0303b9,1f8c:1f0403b9,1f8d:1f0503b9,1f8e:1f0603b9,1f8f:1f0703b9,1f90:1f2003b9,1f91:1f" +
        "2103b9,1f92:1f2203b9,1f93:1f2303b9,1f94:1f2403b9,1f95:1f2503b9,1f96:1f2603b9,1f97:1f2703b9,1f98:1f20" +
        "03b9,1f99:1f2103b9,1f9a:1f2203b9,1f9b:1f2303b9,1f9c:1f2403b9,1f9d:1f2503b9,1f9e:1f2603b9,1f9f:1f2703" +
        "b9,1fa0:1f6003b9,1fa1:1f6103b9,1fa2:1f6203b9,1fa3:1f6303b9,1fa4:1f6403b9,1fa5:1f6503b9,1fa6:1f6603b9" +
        ",1fa7:1f6703b9,1fa8:1f6003b9,1fa9:1f6103b9,1faa:1f6203b9,1fab:1f6303b9,1fac:1f6403b9,1fad:1f6503b9,1" +
        "fae:1f6603b9,1faf:1f6703b9,1fb2:1f7003b9,1fb3:03b103b9,1fb4:03ac03b9,1fb6:03b10342,1fb7:03b1034203b9" +
        ",1fbc:03b103b9,1fbe:03b9,1fc2:1f7403b9,1fc3:03b703b9,1fc4:03ae03b9,1fc6:03b70342,1fc7:03b7034203b9,1" +
        "fcc:03b703b9,1fd2:03b903080300,1fd3:03b903080301,1fd6:03b90342,1fd7:03b903080342,1fe2:03c503080300,1" +
        "fe3:03c503080301,1fe4:03c10313,1fe6:03c50342,1fe7:03c503080342,1ff2:1f7c03b9,1ff3:03c903b9,1ff4:03ce" +
        "03b9,1ff6:03c90342,1ff7:03c9034203b9,1ffc:03c903b9,ab70:13a0,ab71:13a1,ab72:13a2,ab73:13a3,ab74:13a4" +
        ",ab75:13a5,ab76:13a6,ab77:13a7,ab78:13a8,ab79:13a9,ab7a:13aa,ab7b:13ab,ab7c:13ac,ab7d:13ad,ab7e:13ae" +
        ",ab7f:13af,ab80:13b0,ab81:13b1,ab82:13b2,ab83:13b3,ab84:13b4,ab85:13b5,ab86:13b6,ab87:13b7,ab88:13b8" +
        ",ab89:13b9,ab8a:13ba,ab8b:13bb,ab8c:13bc,ab8d:13bd,ab8e:13be,ab8f:13bf,ab90:13c0,ab91:13c1,ab92:13c2" +
        ",ab93:13c3,ab94:13c4,ab95:13c5,ab96:13c6,ab97:13c7,ab98:13c8,ab99:13c9,ab9a:13ca,ab9b:13cb,ab9c:13cc" +
        ",ab9d:13cd,ab9e:13ce,ab9f:13cf,aba0:13d0,aba1:13d1,aba2:13d2,aba3:13d3,aba4:13d4,aba5:13d5,aba6:13d6" +
        ",aba7:13d7,aba8:13d8,aba9:13d9,abaa:13da,abab:13db,abac:13dc,abad:13dd,abae:13de,abaf:13df,abb0:13e0" +
        ",abb1:13e1,abb2:13e2,abb3:13e3,abb4:13e4,abb5:13e5,abb6:13e6,abb7:13e7,abb8:13e8,abb9:13e9,abba:13ea" +
        ",abbb:13eb,abbc:13ec,abbd:13ed,abbe:13ee,abbf:13ef,fb00:00660066,fb01:00660069,fb02:0066006c,fb03:00" +
        "6600660069,fb04:00660066006c,fb05:00730074,fb06:00730074,fb13:05740576,fb14:05740565,fb15:0574056b,f" +
        "b16:057e0576,fb17:0574056d"

    // ⚠️ **Se indexa por `Char` y no por code point, y eso esta verificado en el generador**:
    // ninguno de los pares --ni origen ni destino-- cae fuera del BMP. Iterar por char evita
    // `Character.charCount` y `appendCodePoint`, que son APIs de la JVM y D-017 no las admite
    // fuera de PlatformJvm.kt.
    private val mapa: Map<Char, String> = buildMap {
        for (entrada in ENCODED.split(',')) {
            val corte = entrada.indexOf(':')
            val origen = entrada.substring(0, corte).toInt(16).toChar()
            val destino = entrada.substring(corte + 1)
            val sb = StringBuilder()
            var i = 0
            while (i < destino.length) {
                sb.append(destino.substring(i, i + 4).toInt(16).toChar())
                i += 4
            }
            put(origen, sb.toString())
        }
    }

    /**
     * `toCaseFold()` sobre el repertorio fijado: minusculas y despues la tabla.
     *
     * `lowercase()` va primero porque resuelve la inmensa mayoria de los casos identico en los
     * dos lenguajes (D-004, cero diferencias sobre 133.730 code points); la tabla corrige los
     * 297 donde el estandar pide otra cosa.
     */
    fun fold(text: String): String {
        val bajo = text.lowercase()
        if (bajo.none { it in mapa }) return bajo
        val salida = StringBuilder(bajo.length)
        for (c in bajo) salida.append(mapa[c] ?: c)
        return salida.toString()
    }
}
