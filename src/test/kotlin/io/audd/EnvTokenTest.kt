package io.audd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for v0.2.0 item 1: `AUDD_API_TOKEN` env-var fallback +
 * `AudD.fromEnvironment()`.
 */
class EnvTokenTest {

    @Test
    fun `null apiToken without env var throws IllegalArgumentException with hint`() {
        // resolveToken is internal but exercised through the public ctor.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            AudD(apiToken = null)
        }
        assertTrue(ex.message!!.contains("AUDD_API_TOKEN"))
        assertTrue(ex.message!!.contains("dashboard.audd.io"))
    }

    @Test
    fun `empty apiToken without env var throws`() {
        assertThrows(IllegalArgumentException::class.java) { AudD(apiToken = "   ") }
    }

    @Test
    fun `explicit apiToken bypasses env var`() {
        AudD(apiToken = "test").use { client ->
            assertEquals("test", client.apiToken)
        }
    }
}
