package com.mycarhub.assistant.core

/**
 * 동기화 통신용 최소 JSON (외부 라이브러리 없이 PC·Android 양쪽에서 같은 코드로 쓰기 위해).
 * 객체 → [Map] (순서 유지), 배열 → [List], 정수 → [Long], 소수 → [Double], 그 밖에 String·Boolean·null.
 */
object MiniJson {

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.end()) throw ParseException("trailing data at ${p.pos}")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> sb.append(if (v.isFinite()) v.toString() else throw IllegalArgumentException("non-finite number"))
            is Float -> write(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported ${v::class.java.name}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun end() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            if (end()) throw ParseException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, pos)) throw ParseException("bad literal at $pos")
            pos += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (peek() == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("expected key at $pos")
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw ParseException("expected , or } at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (peek() == ']') { pos++; return l }
            while (true) {
                skipWs()
                l += value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw ParseException("expected , or ] at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (end()) throw ParseException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (end()) throw ParseException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            var fractional = false
            if (pos < s.length && s[pos] == '.') {
                fractional = true
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                fractional = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val t = s.substring(start, pos)
            if (t == "-" || t.isEmpty()) throw ParseException("bad number at $start")
            return if (fractional) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        private fun peek(): Char = if (end()) throw ParseException("unexpected end") else s[pos]

        private fun expect(c: Char) {
            if (peek() != c) throw ParseException("expected '$c' at $pos")
            pos++
        }
    }
}

/** JSON 객체에서 값 꺼내기 (서버 응답 해석용) */
internal fun Map<*, *>.str(key: String): String? = this[key] as? String
internal fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean
