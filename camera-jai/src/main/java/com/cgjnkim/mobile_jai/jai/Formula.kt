package com.cgjnkim.mobile_jai.jai

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.truncate

/**
 * A GenICam SwissKnife formula, parsed once and evaluated against variable values.
 *
 * On this camera nearly every register address is one of these -- `0x05050C +
 * SourceSelectorValue * 4` -- so the formula is what makes a feature per-source at all.
 *
 * Integer formulas (IntSwissKnife, IntConverter) evaluate in 64-bit integers with
 * truncating division, as GenApi does; float formulas (SwissKnife, Converter) in
 * doubles. Comparisons and logic yield 1 or 0.
 */
internal class Formula(val text: String) {

    private val root: Node = Parser(text).parse()

    /** Names the formula refers to, so the caller knows which variables to resolve. */
    val variables: Set<String> by lazy { HashSet<String>().also { root.collect(it) } }

    fun evalLong(vars: (String) -> Double): Long = root.long(vars)
    fun evalDouble(vars: (String) -> Double): Double = root.double(vars)

    private sealed class Node {
        abstract fun double(v: (String) -> Double): Double
        abstract fun long(v: (String) -> Double): Long
        open fun collect(out: MutableSet<String>) {}
    }

    private class Num(val d: Double, val l: Long) : Node() {
        override fun double(v: (String) -> Double) = d
        override fun long(v: (String) -> Double) = l
    }

    private class Var(val name: String) : Node() {
        override fun double(v: (String) -> Double) = v(name)
        override fun long(v: (String) -> Double) = v(name).toLong()
        override fun collect(out: MutableSet<String>) { out += name }
    }

    private class Unary(val op: String, val a: Node) : Node() {
        override fun double(v: (String) -> Double): Double = when (op) {
            "-" -> -a.double(v)
            "+" -> a.double(v)
            "~" -> a.long(v).inv().toDouble()
            "!" -> if (a.double(v) == 0.0) 1.0 else 0.0
            else -> error(op)
        }
        override fun long(v: (String) -> Double): Long = when (op) {
            "-" -> -a.long(v)
            "+" -> a.long(v)
            "~" -> a.long(v).inv()
            "!" -> if (a.long(v) == 0L) 1 else 0
            else -> error(op)
        }
        override fun collect(out: MutableSet<String>) = a.collect(out)
    }

    private class Binary(val op: String, val a: Node, val b: Node) : Node() {
        override fun double(v: (String) -> Double): Double {
            // Short-circuit, so a guarded division by a zero selector is never evaluated.
            if (op == "&&") return if (a.double(v) != 0.0 && b.double(v) != 0.0) 1.0 else 0.0
            if (op == "||") return if (a.double(v) != 0.0 || b.double(v) != 0.0) 1.0 else 0.0
            val x = a.double(v)
            val y = b.double(v)
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> x / y
                "%" -> x % y
                "**" -> x.pow(y)
                "&" -> (x.toLong() and y.toLong()).toDouble()
                "|" -> (x.toLong() or y.toLong()).toDouble()
                "^" -> (x.toLong() xor y.toLong()).toDouble()
                "<<" -> (x.toLong() shl y.toInt()).toDouble()
                ">>" -> (x.toLong() shr y.toInt()).toDouble()
                "=" -> bool(x == y)
                "<>" -> bool(x != y)
                "<" -> bool(x < y)
                ">" -> bool(x > y)
                "<=" -> bool(x <= y)
                ">=" -> bool(x >= y)
                else -> error(op)
            }
        }

        override fun long(v: (String) -> Double): Long {
            if (op == "&&") return if (a.long(v) != 0L && b.long(v) != 0L) 1 else 0
            if (op == "||") return if (a.long(v) != 0L || b.long(v) != 0L) 1 else 0
            val x = a.long(v)
            val y = b.long(v)
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0L) throw ArithmeticException("division by zero in formula") else x / y
                "%" -> if (y == 0L) throw ArithmeticException("modulo by zero in formula") else x % y
                "**" -> x.toDouble().pow(y.toDouble()).toLong()
                "&" -> x and y
                "|" -> x or y
                "^" -> x xor y
                "<<" -> x shl y.toInt()
                ">>" -> x shr y.toInt()
                "=" -> if (x == y) 1 else 0
                "<>" -> if (x != y) 1 else 0
                "<" -> if (x < y) 1 else 0
                ">" -> if (x > y) 1 else 0
                "<=" -> if (x <= y) 1 else 0
                ">=" -> if (x >= y) 1 else 0
                else -> error(op)
            }
        }

        override fun collect(out: MutableSet<String>) { a.collect(out); b.collect(out) }

        private fun bool(b: Boolean) = if (b) 1.0 else 0.0
    }

    private class Ternary(val c: Node, val a: Node, val b: Node) : Node() {
        override fun double(v: (String) -> Double) = if (c.double(v) != 0.0) a.double(v) else b.double(v)
        override fun long(v: (String) -> Double) = if (c.long(v) != 0L) a.long(v) else b.long(v)
        override fun collect(out: MutableSet<String>) { c.collect(out); a.collect(out); b.collect(out) }
    }

    /** Functions are always computed in doubles; an integer formula truncates the result. */
    private class Call(val name: String, val args: List<Node>) : Node() {
        override fun double(v: (String) -> Double): Double {
            val x = args[0].double(v)
            return when (name) {
                "ROUND" -> if (args.size > 1) {
                    val scale = 10.0.pow(args[1].double(v))
                    round(x * scale) / scale
                } else round(x)
                "TRUNC" -> truncate(x)
                "FLOOR" -> floor(x)
                "CEIL" -> ceil(x)
                "ABS" -> abs(x)
                "SGN" -> sign(x)
                "NEG" -> -x
                "SQRT" -> sqrt(x)
                "EXP" -> exp(x)
                "LN" -> ln(x)
                "LG" -> log10(x)
                "SIN" -> sin(x)
                "COS" -> cos(x)
                "TAN" -> tan(x)
                "ASIN" -> asin(x)
                "ACOS" -> acos(x)
                "ATAN" -> atan(x)
                else -> throw IllegalArgumentException("unknown formula function $name")
            }
        }
        override fun long(v: (String) -> Double) = double(v).toLong()
        override fun collect(out: MutableSet<String>) = args.forEach { it.collect(out) }
    }

    /** Precedence climbing, loosest first: ?: || && | ^ & (= <>) (< > <= >=) (<< >>) (+ -) (* / %) ** unary. */
    private class Parser(val src: String) {
        private val tokens = tokenize(src)
        private var i = 0

        fun parse(): Node {
            val n = ternary()
            if (i != tokens.size) throw IllegalArgumentException("trailing '${tokens[i]}' in formula: $src")
            return n
        }

        private fun peek() = tokens.getOrNull(i)
        private fun take() = tokens[i++]
        private fun expect(t: String) {
            if (peek() != t) throw IllegalArgumentException("expected '$t' at ${peek()} in formula: $src")
            i++
        }

        private fun ternary(): Node {
            val c = binary(0)
            if (peek() != "?") return c
            take()
            val a = ternary()
            expect(":")
            val b = ternary()
            return Ternary(c, a, b)
        }

        private fun binary(level: Int): Node {
            if (level == LEVELS.size) return power()
            var left = binary(level + 1)
            while (peek() in LEVELS[level]) {
                val op = take()
                left = Binary(op, left, binary(level + 1))
            }
            return left
        }

        private fun power(): Node {
            val base = unary()
            if (peek() != "**") return base
            take()
            return Binary("**", base, power())
        }

        private fun unary(): Node {
            val t = peek()
            if (t == "-" || t == "+" || t == "~" || t == "!") {
                take()
                return Unary(t, unary())
            }
            return primary()
        }

        private fun primary(): Node {
            val t = take()
            if (t == "(") {
                val n = ternary()
                expect(")")
                return n
            }
            if (t[0].isDigit() || t[0] == '.') return number(t)
            if (t == "PI") return Num(Math.PI, Math.PI.toLong())
            if (t == "E") return Num(Math.E, Math.E.toLong())
            if (peek() == "(") {
                take()
                val args = ArrayList<Node>()
                if (peek() != ")") {
                    args += ternary()
                    while (peek() == ",") { take(); args += ternary() }
                }
                expect(")")
                return Call(t.uppercase(), args)
            }
            return Var(t)
        }

        private fun number(t: String): Node {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                val l = java.lang.Long.parseUnsignedLong(t.substring(2), 16)
                return Num(l.toDouble(), l)
            }
            val d = t.toDouble()
            return Num(d, d.toLong())
        }

        companion object {
            val LEVELS = listOf(
                setOf("||"), setOf("&&"), setOf("|"), setOf("^"), setOf("&"),
                setOf("=", "<>"), setOf("<", ">", "<=", ">="), setOf("<<", ">>"),
                setOf("+", "-"), setOf("*", "/", "%"),
            )
            private val TWO_CHAR = setOf("<<", ">>", "<=", ">=", "<>", "&&", "||", "**")

            fun tokenize(s: String): List<String> {
                val out = ArrayList<String>()
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c.isWhitespace() -> i++
                        c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                            val start = i
                            if (c == '0' && i + 1 < s.length && (s[i + 1] == 'x' || s[i + 1] == 'X')) {
                                i += 2
                                while (i < s.length && s[i].isLetterOrDigit()) i++
                            } else {
                                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                                if (i < s.length && (s[i] == 'e' || s[i] == 'E') &&
                                    i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')
                                ) {
                                    i += 2
                                    while (i < s.length && s[i].isDigit()) i++
                                }
                            }
                            out += s.substring(start, i)
                        }
                        c.isLetter() || c == '_' -> {
                            val start = i
                            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
                            out += s.substring(start, i)
                        }
                        i + 1 < s.length && s.substring(i, i + 2) in TWO_CHAR -> {
                            out += s.substring(i, i + 2); i += 2
                        }
                        else -> { out += c.toString(); i++ }
                    }
                }
                return out
            }
        }
    }
}
