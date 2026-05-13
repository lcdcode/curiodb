package com.lcdcode.curiodb.data

import android.content.Context
import java.io.File

/**
 * Owns the on-disk layout for CurioDB databases inside app-private storage.
 *
 * Layout:
 *   filesDir/
 *     databases/
 *       <id>.curiodb         <- SQLite file
 *     images/
 *       <id>/                <- sibling images folder for that database
 *         <uuid>.jpg
 *
 * Export to user-chosen locations is handled separately via SAF.
 */
class CurioFileManager(private val context: Context) {

    private val databasesDir: File
        get() = File(context.filesDir, "databases").apply { mkdirs() }

    private val imagesRoot: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    fun databaseFile(id: String): File = File(databasesDir, "$id.curiodb")

    fun imagesDir(id: String): File =
        File(imagesRoot, id).apply { mkdirs() }

    fun listDatabaseIds(): List<String> =
        databasesDir.listFiles { f -> f.isFile && f.name.endsWith(".curiodb") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    fun deleteDatabase(id: String) {
        databaseFile(id).delete()
        imagesDir(id).deleteRecursively()
    }
}
