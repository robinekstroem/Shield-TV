package se.norrsken.tv.ipad

/**
 * En liten JSON-läsare och -skrivare. Objekt blir Map, listor List, tal
 * Double. Räcker för Xtream-svaren och för det appen sparar själv.
 */
internal object Json {
    fun parse(text: String): Any? = Reader(text).value()

    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun value(): Any? {
            skip()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skip()
                val key = str()
                skip()
                i++ // :
                map[key] = value()
                skip()
                if (s[i] == ',') {
                    i++
                    continue
                }
                i++ // }
                return map
            }
        }

        private fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list += value()
                skip()
                if (s[i] == ',') {
                    i++
                    continue
                }
                i++ // ]
                return list
            }
        }

        private fun str(): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun num(): Double? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull()
        }
    }
}

/** Ett värde ur ett JSON-svar som text: tal utan decimaler skrivs som heltal. */
internal fun Any?.asText(): String? = when (this) {
    null -> null
    is Double -> if (this % 1.0 == 0.0) toLong().toString() else toString()
    else -> toString()
}
