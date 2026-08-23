package dev.livefork.core.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConvertHelperTest {

    private fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    @Test
    fun str_returnsEmptyForMissingOrNull() {
        assertEquals("", JsonNull.str())
        assertEquals("", obj {}.at("missing").str())
    }

    @Test
    fun str_returnsContentForPrimitive() {
        assertEquals("hello", JsonPrimitive("hello").str())
        assertEquals("123", JsonPrimitive(123).str())
    }

    @Test
    fun intv_parsesIntWithFallback() {
        assertEquals(42, JsonPrimitive("42").intv())
        assertEquals(7, JsonPrimitive(7).intv())
        assertEquals(0, JsonPrimitive("abc").intv())
        assertEquals(0, JsonNull.intv())
    }

    @Test
    fun bool_parsesVariants() {
        assertEquals(true, JsonPrimitive("true").bool())
        assertEquals(true, JsonPrimitive("1").bool())
        assertEquals(true, JsonPrimitive(1).bool())
        assertEquals(false, JsonPrimitive("false").bool())
        assertEquals(false, JsonPrimitive("0").bool())
        assertEquals(true, JsonPrimitive(5).bool())
        assertEquals(false, JsonNull.bool())
    }

    @Test
    fun at_navigatesDottedPath() {
        val data = obj {
            put("list", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        }
        val node = obj { put("data", data) }
        assertEquals("a", node.at("data.list")?.jsonArray?.get(0)?.str())
        assertNull(node.at("data.missing"))
        assertNull(node.at("nope"))
    }

    @Test
    fun asType_matchesValues() {
        assertEquals("x", "x".asType<String>())
        assertNull("x".asType<Int>())
        assertNull(null.asType<String>())
    }
}