package com.gametimer.protocol.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.ExperimentalSerializationApi

/** A protocol violation after the handshake: the agent sends `bye protocol_error` and closes. */
public class ProtocolViolation(message: String) : Exception(message)

/**
 * Parses a message plaintext as I-JSON (RFC 7493, protocol §4.5), like the C# agent's StrictJson: valid UTF-8,
 * a JSON object at the top, no duplicate member names, integers within ±(2^53 − 1), at most 16 levels deep.
 * Anything else is a [ProtocolViolation]. (kotlinx.serialization's tree parser keeps the last of duplicate
 * names, hence this parser.)
 */
internal object StrictJson {
    const val MAX_PLAINTEXT: Int = 65519 // 65535 Noise limit − 16-byte tag
    private const val MAX_DEPTH = 16
    private const val MAX_SAFE_INTEGER = 9007199254740991L // 2^53 − 1

    fun parse(utf8: ByteArray): JsonObject {
        if (utf8.size > MAX_PLAINTEXT) throw ProtocolViolation("message too large")
        val text = try { utf8.decodeToString(throwOnInvalidSequence = true) } catch (e: Exception) { throw ProtocolViolation("invalid UTF-8") }
        val p = Parser(text)
        p.ws()
        val root = p.value(0)
        p.ws()
        if (p.i != text.length) throw ProtocolViolation("trailing data")
        return root as? JsonObject ?: throw ProtocolViolation("message is not a JSON object")
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        fun fail(what: String): Nothing = throw ProtocolViolation("invalid JSON: $what at $i")

        fun value(depth: Int): JsonElement {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> JsonPrimitive(str())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        fun literal(word: String, v: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        fun obj(depth: Int): JsonObject {
            if (depth > MAX_DEPTH) fail("too deep")
            i++ // {
            val map = LinkedHashMap<String, JsonElement>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(map) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected a name")
                val name = str()
                if (map.containsKey(name)) throw ProtocolViolation("duplicate member name")
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                ws()
                map[name] = value(depth)
                ws()
                if (i >= s.length) fail("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject(map) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): JsonArray {
            if (depth > MAX_DEPTH) fail("too deep")
            i++ // [
            val list = ArrayList<JsonElement>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(list) }
            while (true) {
                ws()
                list.add(value(depth))
                ws()
                if (i >= s.length) fail("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(list) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad \\u escape")
                                val code = hex.toInt(16)
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in a string")
                    else -> sb.append(c)
                }
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun num(): JsonElement {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else fail("bad number")
            var integer = true
            if (i < s.length && s[i] == '.') {
                integer = false; i++
                if (i >= s.length || s[i] !in '0'..'9') fail("bad number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integer = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') fail("bad number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val raw = s.substring(start, i)
            if (integer) {
                val v = raw.toLongOrNull() ?: throw ProtocolViolation("integer out of the I-JSON range")
                if (v > MAX_SAFE_INTEGER || v < -MAX_SAFE_INTEGER) throw ProtocolViolation("integer out of the I-JSON range")
                return JsonPrimitive(v)
            }
            val d = raw.toDoubleOrNull() ?: fail("bad number")
            if (d.isInfinite()) throw ProtocolViolation("number out of range")
            return JsonUnquotedLiteral(raw)
        }
    }
}
