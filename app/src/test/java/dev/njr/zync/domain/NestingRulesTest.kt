package dev.njr.zync.domain

import dev.njr.zync.data.NodeKind.FOLDER
import dev.njr.zync.data.NodeKind.PROJECT
import dev.njr.zync.data.NodeKind.TASK
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NestingRulesTest {
    @Test fun `folder nests at root or under folder`() {
        assertTrue(NestingRules.canNest(FOLDER, null))
        assertTrue(NestingRules.canNest(FOLDER, FOLDER))
        assertFalse(NestingRules.canNest(FOLDER, PROJECT))
        assertFalse(NestingRules.canNest(FOLDER, TASK))
    }

    @Test fun `project nests only under folder`() {
        assertTrue(NestingRules.canNest(PROJECT, FOLDER))
        assertFalse(NestingRules.canNest(PROJECT, null))
        assertFalse(NestingRules.canNest(PROJECT, PROJECT))
        assertFalse(NestingRules.canNest(PROJECT, TASK))
    }

    @Test fun `task nests under project task or folder but not root`() {
        assertTrue(NestingRules.canNest(TASK, PROJECT))
        assertTrue(NestingRules.canNest(TASK, TASK))
        assertTrue(NestingRules.canNest(TASK, FOLDER))
        assertFalse(NestingRules.canNest(TASK, null))
    }
}
