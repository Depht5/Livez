package com.kotlin_livez.core.common

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/**
 * Rhino 兼容化预处理：把 dart_quickjs（QuickJS，标准 ES 语义）能跑的抖音签名脚本
 * 转换成 Rhino 1.7.15 能跑的形式。
 *
 * 脚本资源与 Dart 同源、可随时重新提取，所以兼容处理全部在这里做，不改资源本身。
 * 已处理的四类差异（都有回归测试）：
 *
 * 1. **函数形参默认值**（ES6）：Rhino 报 `missing ) after formal parameters`
 *    （如 `function f(a, num = null)` / `suffix = "cus"` / `Arguments = [0, 1, 14]`）
 *    → 移除默认值并在函数体开头注入 `if (x === undefined) x = <默认值>;`（保持语义）。
 * 2. **顶层 `return X;`**：Rhino 报 `invalid return`，quickjs 允许 → 改写成表达式语句。
 * 3. **`let`**：Rhino 1.7.15 存在 let 重声明/作用域缺陷 → 统一降级为 `var`。
 * 4. **块级函数声明的提升**（2026-11-11 抖音弹幕 `w_0x42f5 is not defined` 的真实成因）：
 *    `if (...) { ... }` 块里先调用、后声明（`function w_0x42f5(){...}` 写在块末尾）——
 *    sloppy 模式的 Annex B 在**块进入时**就初始化该函数，QuickJS 照做；Rhino 不做，
 *    于是块内第一句 `var x = w_0x42f5()` 直接 ReferenceError。这里把块级函数声明
 *    前移到所属块的开头（`var NAME = function(...) {...};` 形式），语义等价。
 */
internal object RhinoJsCompat {

    /** 多于一轮是因为函数体内部的块级声明要等外层移动完成后才可见。 */
    private const val MAX_HOIST_PASSES = 4

    /**
     * 打开 Rhino 的 `FEATURE_LITTLE_ENDIAN`（默认关闭）。
     *
     * **这是抖音弹幕「连不上」的根因**（2026-11-11 逐层定位）：
     * `webms_sdk.js` 的 webcast 签名用 `Uint32Array` 与 `Uint8Array` **共用同一个 ArrayBuffer**
     * 做字/字节视图转换；浏览器（V8/QuickJS，以及 x86/ARM 本身）都是**小端**，
     * 而 Rhino 1.7.15 的 TypedArray 默认按**大端**读写。
     *
     * 结果：同一个 msStub/UA 下算出的 signature 与服务端期望的不一致，握手被拒
     * （HTTP 200 + `handshake-status: 415` / `handshake-msg: DEVICE_BLOCKED`），
     * 弹幕一条都收不到。
     *
     * 实证（固定 `Math.random` 与 `Date` 后两端确定性对比）：
     * - V8 小端 → `fZCpNb7LVQUdz7fK`（服务端 5/5 接受）
     * - 在 V8 里把 `Uint32Array` 模拟成大端 → `fZCpNb7LNK9ta73T`，与 Rhino 原始输出**完全一致**
     *   （服务端 0/5 接受）
     *
     * 不用 `ContextFactory.initGlobal(...)`：该 API 全局只能调用一次，且会影响进程内其它
     * Rhino 使用方（斗鱼签名）。这里改为自建工厂并 `enterContext()`，只作用于本模块。
     */
    private val littleEndianFactory: ContextFactory = object : ContextFactory() {
        override fun hasFeature(cx: Context?, featureIndex: Int): Boolean =
            featureIndex == Context.FEATURE_LITTLE_ENDIAN || super.hasFeature(cx, featureIndex)
    }

    /** 用「小端 TypedArray」上下文取代 `Context.enter()`。 */
    fun enterContext(): Context = littleEndianFactory.enterContext()

    fun sanitize(raw: String): String {
        var s = raw
        s = s.replace(Regex("""\blet\s"""), "var ")
        s = fixDefaultParams(s)
        s = s.replace(Regex("""(?m)^return\s+(.+);\s*$"""), "$1;")
        s = hoistBlockFunctions(s)
        return s
    }

    /** 见类注释第 1 条：形参默认值 → 函数体内的 `if (x===undefined) x=默认值;` 注入。 */
    private fun fixDefaultParams(source: String): String =
        // 仅处理函数头参数列表区间，避免误伤普通赋值（如 for 初始化）
        Regex("""(function\s+\w+\s*\()([^)]*?)(\))(\s*\{)""").replace(source) { m ->
            val injections = mutableListOf<String>()
            val params = Regex("""\b(\w+)\s*=\s*(?:"[^"]*"|'[^']*'|\[[^\]]*\]|[^,)]+)""")
                .replace(m.groupValues[2]) { pm ->
                    val name = pm.groupValues[1]
                    val value = pm.groupValues[0].substringAfter('=').trim()
                    injections += "if ($name===undefined) $name=$value;"
                    name
                }
            val injected = if (injections.isEmpty()) "" else " " + injections.joinToString(" ")
            m.groupValues[1] + params + m.groupValues[3] + m.groupValues[4] + injected
        }

    /** 见类注释第 4 条：把块级函数声明前移到所属块开头，直到不再变化。 */
    private fun hoistBlockFunctions(source: String): String {
        var current = source
        repeat(MAX_HOIST_PASSES) {
            val next = hoistOnce(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private class Decl(val start: Int, val endExclusive: Int, val replacement: String)

    private fun hoistOnce(source: String): String {
        val code = codeMask(source)
        // 结构没解析干净（字符串/注释之外的大括号不平衡，例如正则字面量里的大括号）
        // 就整体放弃本步，宁可保留原脚本也不要产出半截代码。
        if (!bracesBalanced(source, code)) return source

        val insertions = LinkedHashMap<Int, StringBuilder>()
        val removals = HashMap<Int, Int>()
        val blockStarts = ArrayDeque<Int>()
        var i = 0
        while (i < source.length) {
            if (!code[i]) {
                i++
                continue
            }
            when {
                source[i] == '{' -> {
                    blockStarts.addLast(i)
                    i++
                }
                source[i] == '}' -> {
                    blockStarts.removeLastOrNull()
                    i++
                }
                source.startsWith("function", i) && isWordBoundary(source, i) -> {
                    val decl = if (startsStatement(source, code, i)) {
                        parseDeclaration(source, code, i)
                    } else {
                        null
                    }
                    if (decl != null && blockStarts.isNotEmpty()) {
                        insertions.getOrPut(blockStarts.last() + 1) { StringBuilder() }
                            .append(decl.replacement).append(' ')
                        removals[decl.start] = decl.endExclusive
                        // 已整体搬走：函数体内部的声明留到下一轮（搬走后的文本上）处理
                        i = decl.endExclusive
                    } else {
                        // 顶层声明（无需前置）或不是语句开头：继续扫描其内部，否则会漏掉块级声明
                        i++
                    }
                }
                else -> i++
            }
        }
        if (removals.isEmpty()) return source

        val out = StringBuilder(source.length + insertions.size * 64)
        var p = 0
        while (p < source.length) {
            insertions[p]?.let { out.append(it) }
            val end = removals[p]
            if (end != null) {
                p = end
                continue
            }
            out.append(source[p])
            p++
        }
        return out.toString()
    }

    /** `function NAME(...) { ... }` → `var NAME = function(...) { ... };` */
    private fun parseDeclaration(source: String, code: BooleanArray, start: Int): Decl? {
        var p = skipSpaces(source, start + "function".length)
        if (p < source.length && source[p] == '*') return null // generator：不处理
        val nameStart = p
        while (p < source.length && isIdentifierPart(source[p])) p++
        if (p == nameStart) return null
        val name = source.substring(nameStart, p)
        p = skipSpaces(source, p)
        if (p >= source.length || source[p] != '(') return null
        val paramsEnd = matchPair(source, code, p, '(', ')') ?: return null
        var bodyStart = skipSpaces(source, paramsEnd + 1)
        if (bodyStart >= source.length || source[bodyStart] != '{') return null
        val bodyEnd = matchPair(source, code, bodyStart, '{', '}') ?: return null

        val params = source.substring(p, paramsEnd + 1)
        val body = source.substring(bodyStart, bodyEnd + 1)
        return Decl(start, bodyEnd + 1, "var $name = function$params $body;")
    }

    /** 标记「代码位置」：字符串、模板串、行/块注释内部为 false。 */
    private fun codeMask(source: String): BooleanArray {
        val n = source.length
        val code = BooleanArray(n) { true }
        var i = 0
        while (i < n) {
            val c = source[i]
            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) n else it }
                    for (k in i until end) code[k] = false
                    i = end
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    for (k in i until end) code[k] = false
                    i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    code[i] = false
                    i++
                    while (i < n) {
                        val ch = source[i]
                        code[i] = false
                        if (ch == '\\') {
                            if (i + 1 < n) code[i + 1] = false
                            i += 2
                            continue
                        }
                        i++
                        if (ch == quote) break
                    }
                }
                else -> i++
            }
        }
        return code
    }

    private fun bracesBalanced(source: String, code: BooleanArray): Boolean {
        var depth = 0
        for (i in source.indices) {
            if (!code[i]) continue
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    private fun matchPair(
        source: String,
        code: BooleanArray,
        openIndex: Int,
        open: Char,
        close: Char,
    ): Int? {
        var depth = 0
        var p = openIndex
        while (p < source.length) {
            if (code[p]) {
                when (source[p]) {
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return p
                    }
                }
            }
            p++
        }
        return null
    }

    private fun skipSpaces(source: String, from: Int): Int {
        var p = from
        while (p < source.length && source[p].isWhitespace()) p++
        return p
    }

    /**
     * `function` 关键字是否处于「语句开头」。
     *
     * 只按上一个非空白字符判断是不够的：混淆脚本里常见
     * `})(w_0x42f5, 0x…)\nfunction w_0x42f5() {…}` —— 上一句以 `)` 结尾，靠 ASI 另起一条语句，
     * 这正是 `w_0x42f5 is not defined` 的位置。因此 `)`/`]`/标识符结尾后若存在换行，也按语句处理。
     */
    private fun startsStatement(source: String, code: BooleanArray, index: Int): Boolean {
        val prevIndex = previousCodeIndex(source, code, index)
        if (prevIndex < 0) return true
        val prev = source[prevIndex]
        return when {
            prev == '{' || prev == '}' || prev == ';' -> true
            prev == ')' || prev == ']' || isIdentifierPart(prev) ->
                hasLineBreak(source, prevIndex + 1, index)
            else -> false
        }
    }

    private fun hasLineBreak(source: String, from: Int, to: Int): Boolean {
        for (p in from until to) {
            val c = source[p]
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') return true
        }
        return false
    }

    private fun previousCodeIndex(source: String, code: BooleanArray, from: Int): Int {
        var p = from - 1
        while (p >= 0) {
            if (code[p] && !source[p].isWhitespace()) return p
            p--
        }
        return -1
    }

    private fun isWordBoundary(source: String, index: Int): Boolean {
        if (index == 0) return true
        val prev = source[index - 1]
        return !(isIdentifierPart(prev) || prev == '.')
    }

    private fun isIdentifierPart(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$'
}
