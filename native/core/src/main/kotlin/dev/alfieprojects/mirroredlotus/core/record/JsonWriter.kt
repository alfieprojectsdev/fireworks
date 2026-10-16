package dev.alfieprojects.mirroredlotus.core.record

import java.util.Locale

/**
 * Minimal streaming JSON writer. `org.json` exists on Android but is a stub on
 * the JVM, and a dependency for one export is not worth it. Non-finite numbers
 * become `null`, since JSON has no NaN. [compact] writes everything on one
 * line, for JSON Lines logs.
 */
class JsonWriter(private val indent: String = "  ", private val compact: Boolean = false) {
    private val sb = StringBuilder()
    private val needsComma = ArrayDeque<Boolean>()
    private var afterKey = false

    fun beginObject() = open('{')
    fun endObject() = close('}')
    fun beginArray() = open('[')
    fun endArray() = close(']')

    fun name(key: String): JsonWriter {
        separator()
        string(key)
        sb.append(if (compact) ":" else ": ")
        afterKey = true
        return this
    }

    fun value(v: String?): JsonWriter {
        separator()
        if (v == null) sb.append("null") else string(v)
        return this
    }

    fun value(v: Number?): JsonWriter {
        separator()
        when {
            v == null -> sb.append("null")
            v is Double && !v.isFinite() -> sb.append("null")
            v is Float && !v.isFinite() -> sb.append("null")
            v is Double || v is Float -> sb.append(formatDecimal(v.toDouble()))
            else -> sb.append(v.toString())
        }
        return this
    }

    fun value(v: Boolean): JsonWriter {
        separator()
        sb.append(v)
        return this
    }

    fun field(key: String, v: String?) = name(key).value(v)
    fun field(key: String, v: Number?) = name(key).value(v)
    fun field(key: String, v: Boolean) = name(key).value(v)

    override fun toString(): String = sb.toString()

    private fun open(c: Char): JsonWriter {
        separator()
        sb.append(c)
        needsComma.addLast(false)
        return this
    }

    private fun close(c: Char): JsonWriter {
        val hadItems = needsComma.removeLast()
        if (hadItems) newline()
        sb.append(c)
        return this
    }

    private fun separator() {
        if (afterKey) {
            afterKey = false
            return
        }
        if (needsComma.isEmpty()) return
        if (needsComma.last()) sb.append(',')
        needsComma[needsComma.size - 1] = true
        newline()
    }

    private fun newline() {
        if (compact) return
        sb.append('\n')
        repeat(needsComma.size) { sb.append(indent) }
    }

    private fun string(s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private fun formatDecimal(d: Double): String {
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }
}
