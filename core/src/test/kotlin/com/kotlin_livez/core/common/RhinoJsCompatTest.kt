package com.kotlin_livez.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RhinoJsCompat] 契约测试。
 *
 * 每条用例都对应一次真实故障：默认参数（Rhino 语法报错）、顶层 return（`invalid return`）、
 * 块级函数声明后置（抖音弹幕 `w_0x42f5 is not defined`）。
 */
class RhinoJsCompatTest {

    @Test
    fun blockFunctionDeclaredAfterUseIsHoistedToBlockStart() {
        val js = """
            if (!window.ready) {
                function caller() { return late() }
                caller();
                function late() { return 42 }
            }
        """.trimIndent()

        val out = RhinoJsCompat.sanitize(js)

        // 声明被前移成 `var late = function ...`，且早于同块内的调用
        val latePos = out.indexOf("var late = function")
        val callPos = out.indexOf("caller()")
        assertTrue(latePos >= 0, "块级声明应被前置: $out")
        assertTrue(callPos > latePos, "调用必须晚于前置后的声明: $out")
        assertTrue(out.indexOf("var caller = function") >= 0, "同块其它声明同样前置: $out")
        assertFalse(out.contains("function late("), "原位置的函数声明应被移除: $out")
    }

    /** `)(…)\nfunction f(){}`：上一句以 `)` 结尾靠 ASI 断句（抖音 `w_0x42f5` 的真实形态）。 */
    @Test
    fun asiTerminatedPreviousStatementStillCountsAsStatementStart() {
        val js = """
            if (a) {
                (function (x) { x() })(late)
                function late() { return 42 }
            }
        """.trimIndent()

        val out = RhinoJsCompat.sanitize(js)

        assertTrue(out.contains("var late = function"), "应识别为语句并前置: $out")
        assertTrue(out.indexOf("var late = function") < out.indexOf("(function (x)"), "必须早于调用: $out")
    }

    @Test
    fun hoistingIsIdempotent() {
        val js = "if (a) { b(); function b() {} }"
        val once = RhinoJsCompat.sanitize(js)
        assertEquals(once, RhinoJsCompat.sanitize(once))
    }

    @Test
    fun functionExpressionIsNotHoisted() {
        val js = "var f = function named() { return 1 };"
        assertEquals(js, RhinoJsCompat.sanitize(js))
    }

    @Test
    fun functionKeywordInsideStringIsUntouched() {
        val js = """var s = "if (x) { function fake() {} }"; var t = 'function fake() {';"""
        assertEquals(js, RhinoJsCompat.sanitize(js))
    }

    @Test
    fun defaultParametersAreRewritten() {
        val out = RhinoJsCompat.sanitize("""function f(a, num = null, suffix = "cus") { return a }""")

        assertTrue(out.contains("function f(a, num, suffix)"), "默认值应被移除: $out")
        assertTrue(out.contains("if (num===undefined) num=null;"), "应注入默认值兜底: $out")
        assertTrue(out.contains("""if (suffix===undefined) suffix="cus";"""), "字符串默认值同样注入: $out")
    }

    @Test
    fun topLevelReturnBecomesExpression() {
        val out = RhinoJsCompat.sanitize("return getSig(x);")
        assertEquals("getSig(x);", out)
    }

    /** 真实资源回归：抖音 webms_sdk.js 里的 `w_0x42f5` 必须被前置到块开头。 */
    @Test
    fun webmsSdkBlockFunctionIsHoistedAheadOfItsUse() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/douyin/webms_sdk.js")) {
            "缺少 webms_sdk.js 资源"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

        val out = RhinoJsCompat.sanitize(raw)

        val decl = out.indexOf("var w_0x42f5 = function")
        // 真正会立刻执行的是字符串表轮转 IIFE：它把 w_0x42f5 当实参传进去
        val firstCall = out.indexOf("})(w_0x42f5")
        assertTrue(decl >= 0, "未识别块级函数声明（decl=$decl raw=${raw.length} out=${out.length}）")
        assertTrue(firstCall >= 0, "脚本结构已变化，找不到入口调用点")
        assertTrue(decl < firstCall, "声明必须早于首次调用：decl=$decl call=$firstCall")
    }

    @Test
    fun unbalancedBracesFallBackToOriginal() {
        // 结构解析不干净（正则字面量里的大括号）时必须原样返回，不能产出半截脚本
        val js = """var re = /\{/; if (a) { b(); function b() {} }"""
        assertEquals(js, RhinoJsCompat.sanitize(js))
    }

    /**
     * 关键回归（2026-11-11 抖音弹幕连不上）：
     * Rhino 的 TypedArray 必须是**小端**，否则 webcast 签名无效。
     *
     * 基准向量来自 V8（与浏览器/QuickJS 一致）：固定 `Math.random()=0.5`、
     * `Date.now()=1700000000000`、msStub=`a611c23a…`、默认 UA 时，
     * 正解为 `fZCpNb7LVQUdz7fK`；Rhino 大端错解为 `fZCpNb7LNK9ta73T`。
     * 该值在服务端 5/5 接受，错解 0/5 接受（HTTP 200 + `DEVICE_BLOCKED`）。
     */
    @Test
    fun webmsSdkSignatureIsLittleEndianAndMatchesBrowserVector() {
        val sdk = RhinoJsCompat.sanitize(
            requireNotNull(javaClass.getResourceAsStream("/douyin/webms_sdk.js")) {
                "缺少 webms_sdk.js 资源"
            }.use { it.readBytes().toString(Charsets.UTF_8) },
        )

        val cx = RhinoJsCompat.enterContext()
        val signature = try {
            cx.optimizationLevel = -1
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                Math.random = function () { return 0.5; };
                var __FIXED = 1700000000000;
                var __OrigDate = Date;
                Date = function (a) { return arguments.length === 0 ? new __OrigDate(__FIXED) : new __OrigDate(a); };
                Date.now = function () { return __FIXED; };
                Date.prototype = __OrigDate.prototype;
                var console = { error: function(){}, log: function(){} };
                """.trimIndent(),
                "<prelude>", 1, null,
            )
            cx.evaluateString(scope, sdk, "<sdk>", 1, null)
            val result = cx.evaluateString(
                scope,
                "getMSSDKSignature('a611c23a6a7ceb9c6d625912efb8f231', " +
                    "'${DouyinSign.defaultUserAgent.replace("'", "\\'")}')",
                "<sig>", 1, null,
            )
            org.mozilla.javascript.Context.toString(result)
        } finally {
            org.mozilla.javascript.Context.exit()
        }

        assertEquals(
            "fZCpNb7LVQUdz7fK",
            signature,
            "签名与浏览器基准不一致：TypedArray 可能又是大端了（会导致抖音弹幕 DEVICE_BLOCKED）",
        )
    }
}
