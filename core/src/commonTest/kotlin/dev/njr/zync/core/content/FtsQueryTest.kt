package dev.njr.zync.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTest {
    @Test
    fun tokenizesLowercasesAndPrefixes() {
        assertEquals(listOf("foo", "bar"), FtsQuery.tokens("Foo, BAR!"))
        assertEquals("foo* bar*", FtsQuery.toMatch("Foo BAR"))
    }

    @Test
    fun punctuationOnlyOrBlankIsNull() {
        assertNull(FtsQuery.toMatch("   "))
        assertNull(FtsQuery.toMatch("!!! ... ---"))
        assertEquals(emptyList(), FtsQuery.tokens("@#$"))
    }

    @Test
    fun stripsOperatorSyntaxToPlainTokens() {
        // Raw FTS operators / quotes are reduced to safe alphanumeric tokens.
        assertEquals(listOf("foo", "or", "bar"), FtsQuery.tokens("foo OR bar\""))
        assertEquals("foo* or* bar*", FtsQuery.toMatch("foo OR bar\""))
    }
}
