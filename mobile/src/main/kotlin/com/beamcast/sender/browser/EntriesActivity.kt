package com.beamcast.sender.browser

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.beamcast.sender.R
import com.beamcast.sender.data.Entry
import com.beamcast.sender.data.Store
import com.beamcast.sender.databinding.ActivityEntriesBinding
import com.beamcast.sender.ui.applySystemBarsPadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Lists bookmarks or history; tapping an entry returns its URL to the browser. */
class EntriesActivity : AppCompatActivity() {
    private lateinit var b: ActivityEntriesBinding
    private lateinit var store: Store
    private var isHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEntriesBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()
        store = Store.get(this)
        isHistory = intent.getBooleanExtra(EXTRA_HISTORY, false)
        b.toolbar.setTitle(if (isHistory) R.string.history_title else R.string.bookmarks_title)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.entries_menu)
        b.toolbar.setOnMenuItemClickListener { onMenu(it) }
        b.list.emptyView = b.empty
        b.list.setOnItemClickListener { _, _, pos, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, current()[pos].url))
            finish()
        }
        b.list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = current()[pos]
            MaterialAlertDialogBuilder(this).setMessage(e.url)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> remove(e) }
                .show()
            true
        }
        refresh()
    }

    private fun list() = if (isHistory) store.history else store.bookmarks
    private fun current(): List<Entry> = list().items

    private fun remove(e: Entry) {
        list().remove(e.url)
        save()
        refresh()
    }

    private fun save() = if (isHistory) store.saveHistory() else store.saveBookmarks()

    private fun onMenu(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear) {
            list().clear(); save(); refresh()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun refresh() {
        val items = current()
        b.list.adapter = object : ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                val e = items[position]
                v.findViewById<TextView>(android.R.id.text1).text = e.title.ifBlank { e.url }
                v.findViewById<TextView>(android.R.id.text2).apply { text = e.url; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                return v
            }
        }
    }

    companion object {
        const val EXTRA_HISTORY = "history"
        const val EXTRA_URL = "url"
    }
}
