package com.cgjnkim.mobile_jai.jai

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/** Where register reads and writes go: the camera over GVCP, or a map in a test. */
interface RegisterPort {
    fun read(address: Long, length: Int): ByteArray
    fun write(address: Long, data: ByteArray)
}

/** A feature the XML does not describe, or describes in a way this map does not follow. */
class GenicamException(message: String) : Exception(message)

/**
 * A GenICam node map: the camera's XML description, evaluated against its registers.
 *
 * Unlike a lookup of literal addresses, this follows the node graph, because on the
 * FS-1600D almost nothing has one. ExposureTime is a Float whose pValue is a FloatReg
 * whose pAddress is an IntSwissKnife over SourceSelector; so is Gain, with GainSelector
 * folded in as well. Resolving those formulas is the only way a feature can be set per
 * source, which is the point of a two-sensor camera.
 *
 * Covered: IntReg, MaskedIntReg, StructEntry, FloatReg, StringReg, Integer, Float,
 * Enumeration, Boolean, Command, String, IntSwissKnife, SwissKnife, Converter,
 * IntConverter -- every node type the JAI's and the Lucid Helios's features are built
 * from. A StructEntry is a MaskedIntReg that takes its address, length and byte order
 * from the StructReg around it; Lucid builds hundreds of bit fields that way. Not covered, because nothing here
 * needs them: pIndex/pValueIndexed, chunk and event ports, and caching. Every read goes
 * to the camera, which costs a round trip but can never be stale.
 *
 * Selectors that live in the host rather than the camera (an Integer or Enumeration with
 * a literal `<Value>`, such as GevStreamChannelSelector or TriggerSelector) are kept in
 * this object; they start at the value the XML gives.
 *
 * Not thread safe; the caller serialises access, as selector-then-value sequences must
 * be atomic anyway.
 */
class NodeMap(xml: String, private val port: RegisterPort) {

    private val nodes = HashMap<String, Element>()
    private val hostValues = HashMap<String, Double>()
    private val formulas = HashMap<String, Formula>()

    init {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        index(doc.documentElement)
    }

    /** Every named node except enum entries, whose names repeat from one enumeration to the next. */
    private fun index(e: Element) {
        var c = e.firstChild
        while (c != null) {
            if (c is Element) {
                val name = c.getAttribute("Name")
                if (name.isNotEmpty() && tag(c) != "EnumEntry") nodes[name] = c
                if (tag(c) != "EnumEntry") index(c)
            }
            c = c.nextSibling
        }
    }

    val names: Set<String> get() = nodes.keys

    fun has(name: String) = nodes.containsKey(name)

    fun type(name: String): String = tag(node(name))

    // ---- public accessors -------------------------------------------------------------

    fun getInt(name: String): Long {
        val e = node(name)
        return when (tag(e)) {
            "Integer" -> text(e, "Value")?.let { hostValues[name]?.toLong() ?: number(it) }
                ?: getInt(pointer(e, "pValue"))
            "IntReg", "MaskedIntReg", "StructEntry" -> readIntReg(e)
            "IntSwissKnife" -> formula(e, "Formula").evalLong(variables(e))
            "IntConverter" -> formula(e, "FormulaFrom").evalLong(variables(e, "TO" to value(pointer(e, "pValue"))))
            "Enumeration" -> enumRaw(e)
            "Boolean" -> if (getBool(name)) 1 else 0
            "Float", "FloatReg", "SwissKnife", "Converter" -> getFloat(name).toLong()
            else -> throw GenicamException("$name is a ${tag(e)}, not an integer")
        }
    }

    fun setInt(name: String, value: Long) {
        val e = node(name)
        when (tag(e)) {
            "Integer" -> if (text(e, "Value") != null) hostValues[name] = value.toDouble()
            else setInt(pointer(e, "pValue"), value)
            "IntReg", "MaskedIntReg", "StructEntry" -> writeIntReg(e, value)
            "IntConverter" -> setNumber(
                pointer(e, "pValue"),
                formula(e, "FormulaTo").evalDouble(variables(e, "FROM" to value.toDouble())),
            )
            "Enumeration" -> setEnumRaw(e, value)
            "Float", "FloatReg", "Converter" -> setFloat(name, value.toDouble())
            else -> throw GenicamException("$name is a ${tag(e)} and cannot be written as an integer")
        }
    }

    fun getFloat(name: String): Double {
        val e = node(name)
        return when (tag(e)) {
            "Float" -> text(e, "Value")?.let { hostValues[name] ?: it.toDouble() } ?: value(pointer(e, "pValue"))
            "FloatReg" -> readFloatReg(e)
            "SwissKnife" -> formula(e, "Formula").evalDouble(variables(e))
            "Converter" -> formula(e, "FormulaFrom").evalDouble(variables(e, "TO" to value(pointer(e, "pValue"))))
            else -> getInt(name).toDouble()
        }
    }

    fun setFloat(name: String, value: Double) {
        val e = node(name)
        when (tag(e)) {
            "Float" -> if (text(e, "Value") != null) hostValues[name] = value
            else setNumber(pointer(e, "pValue"), value)
            "FloatReg" -> writeFloatReg(e, value)
            "Converter" -> setNumber(
                pointer(e, "pValue"),
                formula(e, "FormulaTo").evalDouble(variables(e, "FROM" to value)),
            )
            else -> setInt(name, Math.round(value))
        }
    }

    fun getEnum(name: String): String {
        val e = enumNode(name)
        val raw = enumRaw(e)
        return entries(e).entries.firstOrNull { it.value == raw }?.key
            ?: throw GenicamException("$name holds $raw, which is none of its entries")
    }

    fun setEnum(name: String, entry: String) {
        val e = enumNode(name)
        val v = entries(e)[entry] ?: throw GenicamException("$name has no entry $entry (has ${entries(e).keys})")
        setEnumRaw(e, v)
    }

    fun enumEntries(name: String): Map<String, Long> = entries(enumNode(name))

    /**
     * The entries the device offers right now: those whose pIsImplemented and
     * pIsAvailable hold. Helios gates its operating modes and exposure times this way
     * on the model and the mode. An entry whose condition cannot be read is kept.
     */
    fun availableEntries(name: String): List<String> =
        children(enumNode(name)).filter { tag(it) == "EnumEntry" }.filter { entry ->
            listOf("pIsImplemented", "pIsAvailable").all { p ->
                val ref = text(entry, p) ?: return@all true
                runCatching { getInt(ref) != 0L }.getOrDefault(true)
            }
        }.map { it.getAttribute("Name") }.toList()

    fun getBool(name: String): Boolean {
        val e = node(name)
        if (tag(e) != "Boolean") return getInt(name) != 0L
        val on = text(e, "OnValue")?.let { number(it) } ?: 1L
        return getInt(pointer(e, "pValue")) == on
    }

    fun setBool(name: String, value: Boolean) {
        val e = node(name)
        if (tag(e) != "Boolean") return setInt(name, if (value) 1 else 0)
        val on = text(e, "OnValue")?.let { number(it) } ?: 1L
        val off = text(e, "OffValue")?.let { number(it) } ?: 0L
        setInt(pointer(e, "pValue"), if (value) on else off)
    }

    fun execute(name: String) {
        val e = node(name)
        if (tag(e) != "Command") throw GenicamException("$name is a ${tag(e)}, not a command")
        val v = text(e, "CommandValue")?.let { number(it) }
            ?: pointerOrNull(e, "pCommandValue")?.let { getInt(it) }
            ?: throw GenicamException("$name has no command value")
        setInt(pointer(e, "pValue"), v)
    }

    fun getString(name: String): String {
        val e = node(name)
        return when (tag(e)) {
            "String" -> getString(pointer(e, "pValue"))
            "StringReg" -> {
                val raw = port.read(address(e), length(e))
                val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
                String(raw, 0, end, Charsets.US_ASCII)
            }
            else -> throw GenicamException("$name is a ${tag(e)}, not a string")
        }
    }

    /** Lower bound of an Integer or Float, following pMin; falls through facades to the register. */
    fun min(name: String): Double = bound(name, "Min", "pMin")

    /** Upper bound of an Integer or Float, following pMax. */
    fun max(name: String): Double = bound(name, "Max", "pMax")

    /** Where a feature currently lives, for diagnostics and tests. Resolves selectors as they stand now. */
    fun addressOf(name: String): Long {
        var e = node(name)
        repeat(MAX_HOPS) {
            when (tag(e)) {
                "IntReg", "MaskedIntReg", "StructEntry", "FloatReg", "StringReg", "Register" -> return address(e)
                else -> e = node(pointerOrNull(e, "pValue") ?: throw GenicamException("$name has no register"))
            }
        }
        throw GenicamException("$name: too many hops to a register")
    }

    // ---- registers --------------------------------------------------------------------

    /**
     * Address literals, pAddress nodes and embedded IntSwissKnife formulas all add up,
     * which is how GenICam composes base and offset. A StructEntry's are its StructReg's.
     */
    private fun address(e: Element): Long {
        var a = 0L
        for (c in children(registerOf(e))) {
            when (tag(c)) {
                "Address" -> a += number(c.textContent)
                "pAddress" -> a += getInt(c.textContent.trim())
                "IntSwissKnife" -> a += getInt(c.getAttribute("Name"))
            }
        }
        return a
    }

    /** The element holding a register's address and layout: a StructEntry's StructReg, else itself. */
    private fun registerOf(e: Element): Element =
        if (tag(e) == "StructEntry") e.parentNode as Element else e

    private fun length(e: Element): Int =
        text(e, "Length")?.let { number(it).toInt() } ?: pointerOrNull(e, "pLength")?.let { getInt(it).toInt() }
        ?: throw GenicamException("${e.getAttribute("Name")} has no length")

    private fun littleEndian(e: Element) = text(e, "Endianess") == "LittleEndian"

    private fun readRaw(e: Element): Long {
        val len = length(e)
        val raw = port.read(address(e), len)
        var v = 0L
        if (littleEndian(e)) for (i in raw.indices.reversed()) v = (v shl 8) or (raw[i].toLong() and 0xFF)
        else for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    private fun writeRaw(e: Element, value: Long) {
        val len = length(e)
        val out = ByteArray(len)
        for (i in 0 until len) {
            val shift = 8 * i
            val b = ((value ushr shift) and 0xFF).toByte()
            if (littleEndian(e)) out[i] = b else out[len - 1 - i] = b
        }
        port.write(address(e), out)
    }

    private fun readIntReg(e: Element): Long {
        val raw = readRaw(e)
        val bits = length(e) * 8
        if (tag(e) == "MaskedIntReg" || tag(e) == "StructEntry") {
            val (lsb, msb) = bitRange(e)
            val width = msb - lsb + 1
            val mask = if (width >= 64) -1L else (1L shl width) - 1
            val v = (raw ushr lsb) and mask
            return if (signed(e) && width < 64 && (v shr (width - 1)) and 1L == 1L) v or mask.inv() else v
        }
        return if (signed(e) && bits < 64 && (raw shr (bits - 1)) and 1L == 1L) raw or ((1L shl bits) - 1).inv() else raw
    }

    private fun writeIntReg(e: Element, value: Long) {
        if (tag(e) != "MaskedIntReg" && tag(e) != "StructEntry") return writeRaw(e, value)
        val (lsb, msb) = bitRange(e)
        val width = msb - lsb + 1
        val mask = (if (width >= 64) -1L else (1L shl width) - 1) shl lsb
        val current = readRaw(e)
        writeRaw(e, (current and mask.inv()) or ((value shl lsb) and mask))
    }

    /**
     * A MaskedIntReg's bit range as shifts from the least significant bit. GenICam numbers
     * the bits of a big-endian register from the most significant end, so they are
     * mirrored before use.
     */
    private fun bitRange(e: Element): Pair<Int, Int> {
        val bits = length(e) * 8
        val bit = text(e, "Bit")?.toInt()
        var lsb = bit ?: text(e, "LSB")?.toInt() ?: 0
        var msb = bit ?: text(e, "MSB")?.toInt() ?: (bits - 1)
        if (!littleEndian(e)) {
            lsb = bits - 1 - lsb
            msb = bits - 1 - msb
        }
        return minOf(lsb, msb) to maxOf(lsb, msb)
    }

    private fun signed(e: Element) = text(e, "Sign") == "Signed"

    private fun readFloatReg(e: Element): Double {
        val raw = readRaw(e)
        return if (length(e) == 8) Double.fromBits(raw) else Float.fromBits(raw.toInt()).toDouble()
    }

    private fun writeFloatReg(e: Element, value: Double) =
        writeRaw(e, if (length(e) == 8) value.toRawBits() else value.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)

    // ---- enumerations -----------------------------------------------------------------

    private fun enumNode(name: String): Element {
        val e = node(name)
        if (tag(e) != "Enumeration") throw GenicamException("$name is a ${tag(e)}, not an enumeration")
        return e
    }

    private fun entries(e: Element): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (c in children(e)) {
            if (tag(c) != "EnumEntry") continue
            val v = text(c, "Value") ?: continue
            out[c.getAttribute("Name")] = number(v)
        }
        return out
    }

    private fun enumRaw(e: Element): Long {
        val name = e.getAttribute("Name")
        text(e, "Value")?.let { return hostValues[name]?.toLong() ?: number(it) }
        return getInt(pointer(e, "pValue"))
    }

    private fun setEnumRaw(e: Element, value: Long) {
        val name = e.getAttribute("Name")
        if (text(e, "Value") != null) hostValues[name] = value.toDouble()
        else setInt(pointer(e, "pValue"), value)
    }

    // ---- formulas and plumbing --------------------------------------------------------

    private fun formula(e: Element, tag: String): Formula {
        val key = e.getAttribute("Name") + "/" + tag
        return formulas.getOrPut(key) {
            Formula(text(e, tag) ?: throw GenicamException("${e.getAttribute("Name")} has no $tag"))
        }
    }

    /** Variable lookup for a formula: each pVariable's Name maps to that node's value. */
    private fun variables(e: Element, vararg fixed: Pair<String, Double>): (String) -> Double {
        val map = HashMap<String, String>()
        for (c in children(e)) if (tag(c) == "pVariable") map[c.getAttribute("Name")] = c.textContent.trim()
        val fixedMap = fixed.toMap()
        return { v ->
            fixedMap[v] ?: map[v]?.let { value(it) }
            ?: throw GenicamException("formula of ${e.getAttribute("Name")} refers to unknown $v")
        }
    }

    /** A node's value as a number, whatever kind of node it is: what a formula variable sees. */
    private fun value(name: String): Double = when (tag(node(name))) {
        "Float", "FloatReg", "SwissKnife", "Converter" -> getFloat(name)
        else -> getInt(name).toDouble()
    }

    private fun setNumber(name: String, v: Double) = when (tag(node(name))) {
        "Float", "FloatReg", "Converter" -> setFloat(name, v)
        else -> setInt(name, Math.round(v))
    }

    private fun bound(name: String, literal: String, pointer: String): Double {
        var e = node(name)
        repeat(MAX_HOPS) {
            text(e, literal)?.let { return if (it.startsWith("0x", ignoreCase = true)) number(it).toDouble() else it.toDouble() }
            pointerOrNull(e, pointer)?.let { return value(it) }
            // A Converter's bounds are its raw value's, carried through FormulaFrom: the
            // Triton's ExposureTime is ExposureTimeRaw / 125. A falling formula swaps them.
            if (tag(e) == "Converter") {
                val raw = pointer(e, "pValue")
                val from = formula(e, "FormulaFrom")
                val a = from.evalDouble(variables(e, "TO" to bound(raw, "Min", "pMin")))
                val b = from.evalDouble(variables(e, "TO" to bound(raw, "Max", "pMax")))
                return if (literal == "Min") minOf(a, b) else maxOf(a, b)
            }
            e = node(pointerOrNull(e, "pValue") ?: return if (literal == "Min") Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY)
        }
        throw GenicamException("$name: too many hops looking for $literal")
    }

    private fun node(name: String): Element =
        nodes[name] ?: throw GenicamException("no node named $name")

    private fun pointer(e: Element, tag: String): String =
        pointerOrNull(e, tag) ?: throw GenicamException("${e.getAttribute("Name")} has no $tag")

    private fun pointerOrNull(e: Element, tag: String): String? = text(e, tag)

    /** A child element's text; a StructEntry inherits whatever it does not set from its StructReg. */
    private fun text(e: Element, tag: String): String? {
        for (c in children(e)) if (tag(c) == tag) return c.textContent.trim()
        if (tag(e) == "StructEntry") return text(registerOf(e), tag)
        return null
    }

    private fun children(e: Element): Sequence<Element> = sequence {
        var c: Node? = e.firstChild
        while (c != null) {
            if (c is Element) yield(c)
            c = c.nextSibling
        }
    }

    private fun tag(e: Element): String = e.localName ?: e.tagName

    private fun number(t: String): Long {
        val s = t.trim()
        return if (s.startsWith("0x") || s.startsWith("0X")) java.lang.Long.parseUnsignedLong(s.substring(2), 16)
        else s.toDouble().toLong()
    }

    private companion object {
        const val MAX_HOPS = 6
    }
}

/** A port over plain memory: registers for tests, and for replaying captured register dumps. */
class MemoryPort(private val order: ByteOrder = ByteOrder.BIG_ENDIAN) : RegisterPort {
    val memory = HashMap<Long, Byte>()
    val writes = ArrayList<Pair<Long, ByteArray>>()

    override fun read(address: Long, length: Int) = ByteArray(length) { memory[address + it] ?: 0 }

    override fun write(address: Long, data: ByteArray) {
        writes += address to data
        data.forEachIndexed { i, b -> memory[address + i] = b }
    }

    fun putU32(address: Long, value: Long) =
        ByteBuffer.allocate(4).order(order).putInt(value.toInt()).array().forEachIndexed { i, b -> memory[address + i] = b }

    fun putFloat(address: Long, value: Float) = putU32(address, value.toRawBits().toLong() and 0xFFFFFFFFL)
}

/** Where a GigE Vision device keeps its GenICam description, and reading it out. */
object GenicamXml {

    private const val TAG = "GenicamXml"

    /**
     * The first URL register reads like `Local:JAI_FS-1600x3200D-10GE_V104.zip;F1C10000;BAE1`:
     * a file name, then the hex address and length of the file in device memory. Only
     * `Local:` is followed; the second URL is usually a vendor web address.
     */
    fun load(control: GvcpControl): String? {
        val url = String(control.readMem(Bootstrap.FIRST_URL, Bootstrap.URL_SIZE), Charsets.US_ASCII)
            .substringBefore('\u0000').trim()
        android.util.Log.i(TAG, "genicam url: $url")
        if (!url.startsWith("Local:", ignoreCase = true)) return null
        val parts = url.substring(6).split(';')
        if (parts.size < 3) return null
        val address = parts[1].toLong(16)
        val length = parts[2].toInt(16)
        val raw = control.readMem(address, length)
        return if (parts[0].endsWith(".zip", ignoreCase = true)) unzipFirst(raw) else String(raw, Charsets.UTF_8)
    }

    private fun unzipFirst(raw: ByteArray): String? {
        java.util.zip.ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) return zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        return null
    }
}
