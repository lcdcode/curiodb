package com.lcdcode.curiodb.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class DatabaseSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val recordCount: Int,
    val aggregateSummaries: List<String>
)

class RequiredFieldException(val fieldName: String) :
    IllegalArgumentException("\"$fieldName\" is required.")

/**
 * App-wide repository over every .curiodb file in app-private storage.
 *
 * Each public mutation refreshes [summaries]. Reads happen on Dispatchers.IO.
 */
class CurioRepository(context: Context) {

    private val files = CurioFileManager(context)
    private val _summaries = MutableStateFlow<List<DatabaseSummary>>(emptyList())
    val summaries: StateFlow<List<DatabaseSummary>> = _summaries.asStateFlow()

    private val cache = mutableMapOf<String, CurioDatabase>()
    private val perDbMutex = mutableMapOf<String, Mutex>()
    private val cacheLock = Mutex()
    private val summariesLock = Mutex()

    /**
     * Acquires the per-DB mutex and runs [block] on the cached handle. Opens
     * the file on first access. Each DB is serialized; reads and writes for
     * the same DB never overlap, so SQLITE_BUSY is impossible. Different DBs
     * run concurrently.
     */
    private suspend fun <T> withDb(dbId: String, block: (CurioDatabase) -> T): T {
        val (db, mutex) = cacheLock.withLock {
            val m = perDbMutex.getOrPut(dbId) { Mutex() }
            val d = cache.getOrPut(dbId) {
                CurioDatabase.openOrCreate(files.databaseFile(dbId))
            }
            d to m
        }
        return mutex.withLock { block(db) }
    }

    private suspend fun evict(dbId: String) {
        cacheLock.withLock {
            perDbMutex.remove(dbId)
            cache.remove(dbId)?.let { runCatching { it.close() } }
        }
    }

    fun close() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
        perDbMutex.clear()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = files.listDatabaseIds().mapNotNull { id ->
            runCatching { summarize(id) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
        summariesLock.withLock { _summaries.value = list }
    }

    suspend fun createDatabase(name: String, fields: List<FieldDef>): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val normalized = assignColumnNames(
                fields.mapIndexed { idx, f -> f.copy(position = idx) }
            )
            val def = DatabaseDef(
                id = id,
                name = name.trim(),
                createdAt = now,
                modifiedAt = now,
                fields = normalized
            )
            withDb(id) { it.initializeWith(def) }
            refresh()
            id
        }

    suspend fun deleteDatabase(id: String) = withContext(Dispatchers.IO) {
        evict(id)
        files.deleteDatabase(id)
        refresh()
    }

    suspend fun readDefinition(id: String): DatabaseDef = withContext(Dispatchers.IO) {
        withDb(id) { it.readDefinition() }
    }

    suspend fun listRecords(
        id: String,
        query: RecordQuery = RecordQuery()
    ): Pair<DatabaseDef, List<CurioRecord>> =
        withContext(Dispatchers.IO) {
            withDb(id) { db ->
                val def = db.readDefinition()
                def to db.listRecords(def, query)
            }
        }

    suspend fun readRecord(dbId: String, recordId: Long): Pair<DatabaseDef, CurioRecord?> =
        withContext(Dispatchers.IO) {
            withDb(dbId) { db ->
                val def = db.readDefinition()
                def to db.readRecord(recordId, def)
            }
        }

    suspend fun saveRecord(dbId: String, recordId: Long?, values: Map<String, Any?>): Long =
        withContext(Dispatchers.IO) {
            val newId = withDb(dbId) { db ->
                val def = db.readDefinition()
                validateRequired(def, values)
                if (recordId == null) db.insertRecord(values)
                else { db.updateRecord(recordId, values); recordId }
            }
            refresh()
            newId
        }

    private fun validateRequired(def: DatabaseDef, values: Map<String, Any?>) {
        for (f in def.fields) {
            if (!f.required) continue
            val v = values[f.columnName]
            val empty = v == null || (v is String && v.isBlank())
            if (empty) throw RequiredFieldException(f.displayName)
        }
    }

    /**
     * Derives a column name from [id] that doesn't collide with [taken].
     * Appends `_2`, `_3`, … on collision instead of letting the SQLite UNIQUE
     * constraint crash a partially-populated transaction.
     */
    private fun uniqueColumnName(id: String, taken: Set<String>): String {
        val base = CurioDatabase.columnNameFor(id)
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /**
     * Assigns unique columnNames to [fields] in-order; later fields are
     * disambiguated from earlier ones plus any [existing] names.
     */
    private fun assignColumnNames(
        fields: List<FieldDef>,
        existing: Set<String> = emptySet()
    ): List<FieldDef> {
        val taken = existing.toMutableSet()
        return fields.map { f ->
            val col = uniqueColumnName(f.id, taken)
            taken += col
            f.copy(columnName = col)
        }
    }

    suspend fun deleteRecord(dbId: String, recordId: Long) = withContext(Dispatchers.IO) {
        withDb(dbId) { it.deleteRecord(recordId) }
        refresh()
    }

    fun imagesDir(dbId: String): File = files.imagesDir(dbId)

    // --- Schema mutations ---------------------------------------------

    suspend fun addField(dbId: String, field: FieldDef) = withContext(Dispatchers.IO) {
        withDb(dbId) { db ->
            val taken = db.readDefinition().fields.map { it.columnName }.toSet()
            val col = uniqueColumnName(field.id, taken)
            db.addField(field.copy(columnName = col))
        }
        refresh()
    }

    suspend fun updateFieldMetadata(dbId: String, field: FieldDef) = withContext(Dispatchers.IO) {
        withDb(dbId) { it.updateFieldMetadata(field) }
        refresh()
    }

    suspend fun reorderFields(dbId: String, orderedIds: List<String>) = withContext(Dispatchers.IO) {
        withDb(dbId) { it.reorderFields(orderedIds) }
        refresh()
    }

    suspend fun deleteField(dbId: String, fieldId: String) = withContext(Dispatchers.IO) {
        withDb(dbId) { it.deleteField(fieldId) }
        refresh()
    }

    suspend fun changeFieldType(dbId: String, fieldId: String, newType: FieldType) =
        withContext(Dispatchers.IO) {
            withDb(dbId) { it.changeFieldType(fieldId, newType) }
            refresh()
        }

    /**
     * Atomically applies a type change (if [oldType] differs) and a metadata
     * update for the same field on a single DB handle. Prevents the race where
     * two independent coroutines open separate connections and interleave.
     */
    suspend fun updateFieldFull(dbId: String, updated: FieldDef, oldType: FieldType?) =
        withContext(Dispatchers.IO) {
            withDb(dbId) { db ->
                if (oldType != null && oldType != updated.type) {
                    db.changeFieldType(updated.id, updated.type)
                }
                db.updateFieldMetadata(updated)
            }
            refresh()
        }

    suspend fun nonNullCount(dbId: String, columnName: String): Int = withContext(Dispatchers.IO) {
        withDb(dbId) { it.nonNullCount(columnName) }
    }

    suspend fun distinctValues(dbId: String, columnName: String): List<String> =
        withContext(Dispatchers.IO) {
            withDb(dbId) { it.distinctValues(columnName) }
        }

    // --- Export / Import ----------------------------------------------

    /**
     * Writes a CurioDB export as a ZIP archive containing `data.json` (schema
     * + records, no image bytes) and `images/<name>` for each referenced
     * image. Streams image bytes directly into ZIP entries — never holds
     * them simultaneously in memory.
     */
    suspend fun exportToStream(
        dbId: String,
        output: OutputStream,
        includeImages: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val (def, records) = withDb(dbId) { db ->
            val d = db.readDefinition()
            d to db.listRecords(d)
        }

        val exportRecords = records.map { rec ->
            ExportRecord(
                createdAt = rec.createdAt,
                modifiedAt = rec.modifiedAt,
                values = def.fields.associate { f ->
                    f.columnName to encodeValue(rec.values[f.columnName], f.type)
                }
            )
        }

        val payload = CurioExport(
            database = ExportDatabase(
                name = def.name,
                createdAt = def.createdAt,
                modifiedAt = def.modifiedAt,
                schemaVersion = def.schemaVersion,
                fields = def.fields.map { it.toExport() }
            ),
            records = exportRecords,
            images = emptyMap() // images are siblings in the ZIP now
        )

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            val jsonBytes = EXPORT_JSON
                .encodeToString(CurioExport.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
            zip.write(jsonBytes)
            zip.closeEntry()

            if (includeImages) {
                val imagesDir = files.imagesDir(dbId)
                for (name in collectImageFilenames(def, records)) {
                    val file = File(imagesDir, name)
                    if (!file.isFile) continue
                    zip.putNextEntry(ZipEntry("images/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    data class ImportResult(val dbId: String, val imported: Int, val skipped: Int)

    /**
     * Accepts both the new ZIP format (data.json + images/) and the legacy
     * JSON-with-base64 format. Detects by peeking the PK\x03\x04 magic.
     *
     * On any failure during read, parse, or insert, fully unwinds: removes the
     * extracted images directory, evicts the cached DB handle, and deletes the
     * partially-initialized database file. Callers see all-or-nothing.
     */
    suspend fun importFromStream(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val buffered = BufferedInputStream(input)
        val newId = UUID.randomUUID().toString()
        val result = try {
            if (looksLikeZip(buffered)) importZip(buffered, newId)
            else importJson(buffered, newId)
        } catch (t: Throwable) {
            cleanupFailedImport(newId)
            throw t
        }
        refresh()
        result
    }

    private suspend fun cleanupFailedImport(newId: String) {
        runCatching { evict(newId) }
        runCatching { files.imagesDir(newId).deleteRecursively() }
        runCatching { files.deleteDatabase(newId) }
    }

    private fun looksLikeZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val magic = ByteArray(4)
        val n = input.read(magic)
        input.reset()
        return n == 4 &&
            magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte() ||
                magic[2] == 0x07.toByte())
    }

    private suspend fun importJson(input: InputStream, newId: String): ImportResult {
        val bytes = readBounded(input, MAX_JSON_SIZE, "JSON payload")
        val payload = parsePayload(bytes.toString(Charsets.UTF_8))
        val (imported, skipped) = insertRecords(newId, payload)

        if (payload.images.isNotEmpty()) {
            val dir = files.imagesDir(newId).apply { mkdirs() }
            for ((name, encoded) in payload.images) {
                val safe = sanitizeImageName(name) ?: continue
                runCatching {
                    File(dir, safe).writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                }
            }
        }
        return ImportResult(newId, imported, skipped)
    }

    private suspend fun importZip(input: InputStream, newId: String): ImportResult {
        val imagesDir = files.imagesDir(newId).apply { mkdirs() }
        var payloadText: String? = null
        var entries = 0
        var totalBytes = 0L

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries++
                if (entries > MAX_ZIP_ENTRIES) {
                    error("Archive has too many entries (>$MAX_ZIP_ENTRIES).")
                }
                val name = entry.name
                val remainingTotal = MAX_TOTAL_SIZE - totalBytes
                if (remainingTotal <= 0) {
                    error("Archive exceeds total size limit (${MAX_TOTAL_SIZE / (1024 * 1024)} MB).")
                }
                val perEntryCap = minOf(MAX_ENTRY_SIZE, remainingTotal)
                when {
                    entry.isDirectory -> { /* skip */ }
                    name == "data.json" -> {
                        val cap = minOf(MAX_JSON_SIZE, perEntryCap)
                        val bytes = readBounded(zip, cap, "data.json")
                        totalBytes += bytes.size
                        payloadText = bytes.toString(Charsets.UTF_8)
                    }
                    name.startsWith("images/") -> {
                        val safe = sanitizeImageName(name.removePrefix("images/"))
                        if (safe != null) {
                            File(imagesDir, safe).outputStream().use { out ->
                                val written = copyBounded(zip, out, perEntryCap, safe)
                                totalBytes += written
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val text = payloadText ?: error("Export archive is missing data.json.")
        val payload = parsePayload(text)
        val (imported, skipped) = insertRecords(newId, payload)
        return ImportResult(newId, imported, skipped)
    }

    /**
     * Copies up to [max] bytes from [src] to [dst], aborting if more arrive.
     * Used to enforce per-entry and total-archive size caps during ZIP import.
     */
    private fun copyBounded(
        src: InputStream,
        dst: OutputStream,
        max: Long,
        label: String
    ): Long {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) {
                error("\"$label\" exceeds size limit (${max / (1024 * 1024)} MB).")
            }
            dst.write(buf, 0, n)
        }
        return total
    }

    private fun readBounded(src: InputStream, max: Long, label: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyBounded(src, out, max, label)
        return out.toByteArray()
    }

    /**
     * Reduces an arbitrary path-like name from an export to a safe basename.
     * Rejects empty strings and `.`/`..` so callers can `?: continue` cleanly.
     */
    private fun sanitizeImageName(name: String): String? {
        val basename = name.trim().replace('\\', '/').substringAfterLast('/')
        if (basename.isEmpty() || basename == "." || basename == "..") return null
        return basename
    }

    private fun parsePayload(text: String): CurioExport {
        val payload = EXPORT_JSON.decodeFromString(CurioExport.serializer(), text)
        require(payload.format == "curiodb") { "Not a CurioDB export file." }
        require(payload.version == 1) { "Unsupported CurioDB export version ${payload.version}." }
        return payload
    }

    private suspend fun insertRecords(newId: String, payload: CurioExport): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val draftFields = payload.database.fields.mapIndexed { idx, f ->
            FieldDef(
                id = f.id,
                columnName = "",
                displayName = f.displayName,
                type = f.type,
                position = idx,
                required = f.required,
                showOnCard = f.showOnCard,
                aggregate = f.aggregate,
                enumOptions = f.enumOptions
            )
        }
        val fields = assignColumnNames(draftFields)
        // Pair each new field with the exported field that produced it so we
        // can look up record values by the export's original columnName,
        // regardless of how the local algorithm names things.
        val byNewCol = fields.zip(payload.database.fields).associate { (newF, exported) ->
            newF.columnName to exported.columnName
        }
        val def = DatabaseDef(
            id = newId,
            name = payload.database.name,
            createdAt = payload.database.createdAt,
            modifiedAt = now,
            schemaVersion = payload.database.schemaVersion,
            fields = fields
        )

        var imported = 0
        var skipped = 0
        withDb(newId) { db ->
            db.initializeWith(def, enforceNotNull = false)
            for (rec in payload.records) {
                val values = fields.associate { f ->
                    val raw = decodeValue(rec.values[byNewCol.getValue(f.columnName)], f.type)
                    val safe = if (f.type == FieldType.IMAGE && raw is String) {
                        sanitizeImageName(raw)
                    } else raw
                    f.columnName to safe
                }
                runCatching { db.insertRecord(values) }
                    .onSuccess { imported++ }
                    .onFailure { skipped++ }
            }
        }
        return imported to skipped
    }

    private fun collectImageFilenames(def: DatabaseDef, records: List<CurioRecord>): Set<String> {
        val imageCols = def.fields.filter { it.type == FieldType.IMAGE }.map { it.columnName }
        if (imageCols.isEmpty()) return emptySet()
        val out = mutableSetOf<String>()
        for (rec in records) {
            for (col in imageCols) {
                val v = rec.values[col] as? String
                if (!v.isNullOrBlank()) out += v
            }
        }
        return out
    }

    private fun FieldDef.toExport(): ExportField = ExportField(
        id = id,
        columnName = columnName,
        displayName = displayName,
        type = type,
        position = position,
        required = required,
        showOnCard = showOnCard,
        aggregate = aggregate,
        enumOptions = enumOptions
    )

    private fun encodeValue(raw: Any?, type: FieldType): JsonElement {
        if (raw == null) return JsonNull
        return when (type) {
            FieldType.TEXT, FieldType.IMAGE, FieldType.ENUM,
            FieldType.AUTOCOMPLETE -> JsonPrimitive(raw as String)
            FieldType.NUMBER -> JsonPrimitive(raw as Double)
            FieldType.INTEGER, FieldType.DATE -> JsonPrimitive(raw as Long)
            FieldType.BOOLEAN -> JsonPrimitive((raw as Long) == 1L)
            FieldType.CHECKLIST -> {
                val stored = raw as String
                val parsed = runCatching {
                    EXPORT_JSON.decodeFromString(
                        ListSerializer(String.serializer()),
                        stored
                    )
                }.getOrDefault(emptyList())
                buildJsonArray { parsed.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private fun decodeValue(element: JsonElement?, type: FieldType): Any? {
        if (element == null || element is JsonNull) return null
        return when (type) {
            FieldType.CHECKLIST -> {
                val arr: JsonArray = (element as? JsonArray)
                    ?: (element as? JsonPrimitive)?.let {
                        runCatching {
                            EXPORT_JSON.parseToJsonElement(it.content).jsonArray
                        }.getOrNull()
                    }
                    ?: return null
                val labels = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                EXPORT_JSON.encodeToString(
                    ListSerializer(String.serializer()),
                    labels
                )
            }
            else -> {
                val prim = element as? JsonPrimitive ?: return null
                when (type) {
                    FieldType.TEXT, FieldType.IMAGE, FieldType.ENUM,
                    FieldType.AUTOCOMPLETE -> prim.contentOrNull
                    FieldType.NUMBER -> prim.doubleOrNull
                    FieldType.INTEGER, FieldType.DATE -> prim.longOrNull
                        ?: prim.doubleOrNull?.let { d ->
                            if (d.isFinite() &&
                                d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
                            ) d.toLong() else null
                        }
                    FieldType.BOOLEAN -> when {
                        prim.booleanOrNull != null -> if (prim.boolean) 1L else 0L
                        prim.longOrNull != null -> if (prim.longOrNull != 0L) 1L else 0L
                        else -> null
                    }
                    FieldType.CHECKLIST -> null // handled above
                }
            }
        }
    }

    private suspend fun summarize(id: String): DatabaseSummary = withDb(id) { db ->
        val def = db.readDefinition()
        DatabaseSummary(
            id = def.id,
            name = def.name,
            createdAt = def.createdAt,
            modifiedAt = def.modifiedAt,
            recordCount = db.recordCount(),
            aggregateSummaries = db.aggregateSummaries(def)
        )
    }

    companion object {
        private const val MAX_ZIP_ENTRIES = 10_000
        private const val MAX_ENTRY_SIZE: Long = 100L * 1024 * 1024
        private const val MAX_TOTAL_SIZE: Long = 500L * 1024 * 1024
        private const val MAX_JSON_SIZE: Long = 50L * 1024 * 1024

        private val EXPORT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
