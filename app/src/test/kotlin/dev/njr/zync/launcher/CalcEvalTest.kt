package dev.njr.zync.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search overlay's inline calculator: parsing, precedence, and the math pre-filter. */
class CalcEvalTest {
    @Test
    fun respectsPrecedenceAndParens() {
        assertEquals("14", CalcEval.eval("2+3*4"))
        assertEquals("20", CalcEval.eval("(2+3)*4"))
    }

    @Test
    fun handlesDecimalsAndFormatting() {
        assertEquals("0.25", CalcEval.eval("1/4"))
        assertEquals("2.5", CalcEval.eval("5/2"))
        assertEquals("0.333333", CalcEval.eval("1/3"))
        assertEquals("391", CalcEval.eval("17*23"))
    }

    @Test
    fun powerIsRightAssociative() {
        assertEquals("512", CalcEval.eval("2^3^2"))
    }

    @Test
    fun unaryMinus() {
        assertEquals("-2", CalcEval.eval("-5+3"))
    }

    @Test
    fun remainder() {
        assertEquals("2", CalcEval.eval("17%5"))
    }

    @Test
    fun divisionByZeroIsNull() {
        assertNull(CalcEval.eval("1/0"))
        assertNull(CalcEval.eval("5%0"))
    }

    @Test
    fun malformedExpressionsAreNull() {
        assertNull(CalcEval.eval("2++3"))
        assertNull(CalcEval.eval("2+"))
        assertNull(CalcEval.eval("(2"))
        assertNull(CalcEval.eval(""))
    }

    @Test
    fun looksLikeMathAcceptsExpressions() {
        assertTrue(CalcEval.looksLikeMath("17*23"))
        assertTrue(CalcEval.looksLikeMath("-5+3"))
        assertTrue(CalcEval.looksLikeMath("(2+3) * 4"))
        assertTrue(CalcEval.looksLikeMath("2^10"))
    }

    @Test
    fun looksLikeMathRejectsSearches() {
        assertFalse(CalcEval.looksLikeMath("42"))
        assertFalse(CalcEval.looksLikeMath("gmail"))
        assertFalse(CalcEval.looksLikeMath("2 fast 2 furious"))
        assertFalse(CalcEval.looksLikeMath("+-*/"))
        assertFalse(CalcEval.looksLikeMath(""))
    }
}
