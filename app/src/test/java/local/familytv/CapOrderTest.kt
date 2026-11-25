package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Test

class CapOrderTest {
    @Test fun underCapMovesKeyToEndAndEvictsNothing() {
        val (kept, removed) = capOrder(listOf("a", "b", "c"), "b", 5)
        assertEquals(listOf("a", "c", "b"), kept)
        assertEquals(emptyList<String>(), removed)
    }

    @Test fun overCapEvictsOldest() {
        val (kept, removed) = capOrder(listOf("a", "b", "c"), "d", 3)
        assertEquals(listOf("b", "c", "d"), kept)
        assertEquals(listOf("a"), removed)
    }

    @Test fun reinsertingExistingKeyDoesNotGrow() {
        val (kept, removed) = capOrder(listOf("a", "b", "c"), "a", 3)
        assertEquals(listOf("b", "c", "a"), kept)
        assertEquals(emptyList<String>(), removed)
    }

    @Test fun firstKeyIntoEmptyList() {
        val (kept, removed) = capOrder(emptyList(), "a", 3)
        assertEquals(listOf("a"), kept)
        assertEquals(emptyList<String>(), removed)
    }

    @Test fun evictsMultipleWhenFarOverCap() {
        val (kept, removed) = capOrder(listOf("a", "b", "c", "d"), "e", 2)
        assertEquals(listOf("d", "e"), kept)
        assertEquals(listOf("a", "b", "c"), removed)
    }
}
