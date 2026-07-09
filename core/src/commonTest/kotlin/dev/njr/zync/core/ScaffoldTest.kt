package dev.njr.zync.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test proving the `:core` KMP module compiles and runs its common test
 * suite on every configured target (JVM + Android). Real coverage arrives with
 * the ULID/HLC/op-model/merge tasks (M3 Task 2+).
 */
class ScaffoldTest {
    @Test
    fun coreModuleRunsCommonTests() {
        assertTrue(true)
    }
}
