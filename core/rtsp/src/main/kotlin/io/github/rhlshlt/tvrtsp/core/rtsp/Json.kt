package io.github.rhlshlt.tvrtsp.core.rtsp

/** A small JSON writer for reports; avoids a serialization dependency in this pure-Kotlin module. */
object Json {
    fun stringify(value: Any?, indent: String = "  "): String = StringBuilder().also { write(it, value, indent, 0) }.toString()

    private fun write(sb: StringBuilder, value: Any?, indent: String, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> string(sb, value)
            is Boolean -> sb.append(value)
            is Double -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value)
            is Float -> write(sb, value.toDouble(), indent, depth)
            is Number -> sb.append(value)
            is Enum<*> -> string(sb, value.name)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    repeat(depth + 1) { sb.append(indent) }
                    string(sb, k.toString())
                    sb.append(": ")
                    write(sb, v, indent, depth + 1)
                }
                sb.append('\n')
                repeat(depth) { sb.append(indent) }
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",\n")
                    repeat(depth + 1) { sb.append(indent) }
                    write(sb, v, indent, depth + 1)
                }
                sb.append('\n')
                repeat(depth) { sb.append(indent) }
                sb.append(']')
            }
            else -> string(sb, value.toString())
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
