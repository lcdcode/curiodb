package com.lcdcode.curiodb.data

import android.content.Context

/**
 * Persists the last-used [SortSpec] per database so reopening a database
 * restores whatever sort the user had set. Stored in a small SharedPreferences
 * file — sort state isn't sensitive and doesn't need to live inside the .db.
 */
class SortPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("curiodb_sort", Context.MODE_PRIVATE)

    fun load(dbId: String): SortSpec = SortSpec(
        columnName = prefs.getString(colKey(dbId), null),
        descending = prefs.getBoolean(descKey(dbId), true)
    )

    fun save(dbId: String, sort: SortSpec) {
        prefs.edit().apply {
            if (sort.columnName == null) remove(colKey(dbId))
            else putString(colKey(dbId), sort.columnName)
            putBoolean(descKey(dbId), sort.descending)
        }.apply()
    }

    fun clear(dbId: String) {
        prefs.edit().remove(colKey(dbId)).remove(descKey(dbId)).apply()
    }

    private fun colKey(dbId: String) = "$dbId.col"
    private fun descKey(dbId: String) = "$dbId.desc"
}
