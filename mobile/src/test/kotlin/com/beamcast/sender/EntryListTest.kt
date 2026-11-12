package com.beamcast.sender

import com.beamcast.sender.data.Entry
import com.beamcast.sender.data.EntryList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryListTest {
    @Test fun addDedupesAndMovesToFront() {
        val l = EntryList()
        l.add(Entry("A", "https://a", 1)); l.add(Entry("B", "https://b", 2)); l.add(Entry("A2", "https://a", 3))
        assertEquals(listOf("https://a", "https://b"), l.items.map { it.url })
        assertEquals("A2", l.items[0].title)
    }

    @Test fun respectsMaxAndRemove() {
        val l = EntryList(max = 2)
        l.add(Entry("1", "u1")); l.add(Entry("2", "u2")); l.add(Entry("3", "u3"))
        assertEquals(listOf("u3", "u2"), l.items.map { it.url })
        assertTrue(l.remove("u2")); assertFalse(l.remove("zzz"))
        l.clear(); assertTrue(l.items.isEmpty())
    }

    @Test fun jsonRoundTripAndBadInput() {
        val l = EntryList(); l.add(Entry("Título ñ", "https://a/?q=1", 5))
        assertEquals(l.items, EntryList.fromJson(l.toJson()).items)
        assertTrue(EntryList.fromJson("not json").items.isEmpty())
        assertTrue(EntryList.fromJson(null).items.isEmpty())
    }
}
