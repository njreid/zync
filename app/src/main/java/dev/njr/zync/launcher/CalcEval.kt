package dev.njr.zync.launcher

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Inline calculator behind the "= result" row in the search overlay. A tiny
 * recursive-descent parser (no script engines, nothing to inject) so typing
 * "17*23" answers instantly and offline; anything malformed simply yields no
 * row instead of an error.
 */
object CalcEval {
    private const val OPERATORS = "+-*/%^("
    private const val ALLOWED = ".,+-*/%^()"

    /**
     * Cheap pre-filter so the overlay only evaluates queries that plausibly are
     * arithmetic: at least one digit, at least one operator (a bare "42" is a
     * search, "-5+3" is math), and no characters outside the expression alphabet.
     */
    fun looksLikeMath(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        if (q.none { it.isDigit() }) return false
        if (q.none { it in OPERATORS }) return false
        return q.all { it.isDigit() || it.isWhitespace() || it in ALLOWED }
    }

    /**
     * Evaluates [query] and returns a display string, or null for anything
     * malformed (unbalanced parens, trailing operator, division by zero, …) —
     * null means "show no calculator row", never an error message.
     */
    fun eval(query: String): String? {
        val value = Parser(query.trim()).parse() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return format(value)
    }

    /** Integers render bare ("391"); otherwise up to 6 decimals, trailing zeros trimmed ("2.5"). */
    private fun format(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
    }

    /**
     * Grammar (lowest to highest binding): `+ -` < `* / %` < unary `-` < `^`
     * (right-associative) < number | `( … )`. Every rule returns null on the
     * first malformed token and the null propagates straight out.
     */
    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Double? {
            val value = expression() ?: return null
            skipWhitespace()
            return if (pos == src.length) value else null
        }

        private fun expression(): Double? {
            var left = term() ?: return null
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '+' -> { pos++; left += term() ?: return null }
                    '-' -> { pos++; left -= term() ?: return null }
                    else -> return left
                }
            }
        }

        private fun term(): Double? {
            var left = factor() ?: return null
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '*' -> { pos++; left *= factor() ?: return null }
                    '/' -> {
                        pos++
                        val right = factor() ?: return null
                        if (right == 0.0) return null
                        left /= right
                    }
                    '%' -> {
                        pos++
                        val right = factor() ?: return null
                        if (right == 0.0) return null
                        left %= right
                    }
                    else -> return left
                }
            }
        }

        private fun factor(): Double? {
            skipWhitespace()
            if (peek() == '-') {
                pos++
                return factor()?.let { -it }
            }
            return power()
        }

        private fun power(): Double? {
            val base = atom() ?: return null
            skipWhitespace()
            if (peek() == '^') {
                pos++
                // Recursing through factor makes ^ right-associative: 2^3^2 = 2^9.
                val exponent = factor() ?: return null
                return base.pow(exponent)
            }
            return base
        }

        private fun atom(): Double? {
            skipWhitespace()
            if (peek() == '(') {
                pos++
                val value = expression() ?: return null
                skipWhitespace()
                if (peek() != ')') return null
                pos++
                return value
            }
            return number()
        }

        private fun number(): Double? {
            val start = pos
            var sawDot = false
            while (pos < src.length) {
                val c = src[pos]
                if (c == '.') {
                    if (sawDot) return null
                    sawDot = true
                } else if (!c.isDigit()) {
                    break
                }
                pos++
            }
            val text = src.substring(start, pos)
            if (text.none { it.isDigit() }) return null
            return text.toDoubleOrNull()
        }

        private fun peek(): Char? = src.getOrNull(pos)

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
