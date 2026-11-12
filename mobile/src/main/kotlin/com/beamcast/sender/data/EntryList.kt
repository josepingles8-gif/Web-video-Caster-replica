package com.beamcast.sender.data

import com.beamcast.protocol.BeamProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Entry(val title: String, val url: String, val time: Long = 0)

/** Ordered (newest first) list of pages, used for both bookmarks and history. Pure logic + JSON. */
class EntryList(initial: List<Entry> = emptyList(), private val max: Int = Int.MAX_VALUE) {
    var items: List<Entry> = initial.take(max)
        private set

    fun add(e: Entry) {
        items = (listOf(e) + items.filterNot { it.url == e.url }).take(max)
    }

    fun remove(url: String): Boolean {
        val n = items.filterNot { it.url == url }
        val changed = n.size != items.size
        items = n
        return changed
    }

    fun contains(url: String) = items.any { it.url == url }
    fun clear() { items = emptyList() }

    fun toJson(): String = BeamProtocol.json.encodeToString(ListSerializer(Entry.serializer()), items)

    companion object {
        fun fromJson(s: String?, max: Int = Int.MAX_VALUE): EntryList {
            if (s.isNullOrBlank()) return EntryList(max = max)
            return try {
                EntryList(BeamProtocol.json.decodeFromString(ListSerializer(Entry.serializer()), s), max)
            } catch (e: Exception) { EntryList(max = max) }
        }
    }
}
