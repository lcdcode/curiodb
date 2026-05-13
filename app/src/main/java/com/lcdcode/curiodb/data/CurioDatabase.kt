package com.lcdcode.curiodb.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns a single .curiodb file (a SQLite database).
 *
 * Layout:
 *   _meta(key TEXT PRIMARY KEY, value TEXT)
 *     - id, name, createdAt, modifiedAt, schemaVersion
 *   _fields(id TEXT PRIMARY KEY, column_name TEXT, display_name TEXT,
 *           type TEXT, position INTEGER, required INTEGER,
 *           show_on_card INTEGER, aggregate TEXT NULL, enum_options TEXT)
 *   records(_id INTEGER PRIMARY KEY AUTOINCREMENT,
 *           _created INTEGER, _modified INTEGER,
 *           c_<id_suffix> <affinity> ...)
 *
 * One DatabaseDef per file. The records table column names are derived
 * deterministically from FieldDef.id (see [columnNameFor]) so display name
 * edits never require a column rename — important because SQLite ALTER
 * TABLE RENAME COLUMN isn't available on older Android (min SDK 26 = SQLite 3.18).
 */
class CurioDatabase private constructor(
    private val file: File,
    private val db: SQLiteDatabase
) : AutoCloseable {

    companion object {
        const val RECORDS_TABLE = "records"

        private val json = Json { ignoreUnknownKeys = true }

        fun columnNameFor(fieldId: String): String =
            "c_" + fieldId.filter { it.isLetterOrDigit() }.take(16).lowercase()

        private val SAFE_COLUMN = Regex("^c_[a-z0-9]+(?:_\\d+)?$")

        /**
         * Boundary check for column names that get interpolated into SQL.
         * All names should originate from [columnNameFor] or the collision
         * disambiguator (`_<n>` suffix); this catches a future caller that
         * passes raw user input.
         */
        private fun requireSafeColumn(name: String) {
            require(SAFE_COLUMN.matches(name)) { "Unsafe column name: $name" }
        }

        fun openOrCreate(file: File): CurioDatabase {
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            ensureMetaTables(db)
            return CurioDatabase(file, db)
        }

        private fun ensureMetaTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS _meta(
                       key TEXT PRIMARY KEY NOT NULL,
                       value TEXT NOT NULL
                   )"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS _fields(
                       id TEXT PRIMARY KEY NOT NULL,
                       column_name TEXT NOT NULL UNIQUE,
                       display_name TEXT NOT NULL,
                       type TEXT NOT NULL,
                       position INTEGER NOT NULL,
                       required INTEGER NOT NULL DEFAULT 0,
                       show_on_card INTEGER NOT NULL DEFAULT 0,
                       aggregate TEXT,
                       enum_options TEXT NOT NULL DEFAULT '[]'
                   )"""
            )
        }
    }

    /**
     * Initialize a brand-new database file with [def]. Idempotent for an empty file.
     *
     * When [enforceNotNull] is false, the records table is created without
     * NOT NULL column constraints. Used by import paths so that legacy rows
     * with missing required fields can still be loaded; the `required` flag
     * remains in `_fields` metadata for the UI to honor on future edits.
     */
    fun initializeWith(def: DatabaseDef, enforceNotNull: Boolean = true) {
        db.beginTransaction()
        try {
            putMeta("id", def.id)
            putMeta("name", def.name)
            putMeta("createdAt", def.createdAt.toString())
            putMeta("modifiedAt", def.modifiedAt.toString())
            putMeta("schemaVersion", def.schemaVersion.toString())

            for (field in def.fields) {
                insertFieldRow(field)
            }
            createRecordsTable(def.fields, enforceNotNull)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readDefinition(): DatabaseDef {
        val meta = readAllMeta()
        val fields = readFields()
        return DatabaseDef(
            id = meta.getValue("id"),
            name = meta.getValue("name"),
            createdAt = meta.getValue("createdAt").toLong(),
            modifiedAt = meta.getValue("modifiedAt").toLong(),
            schemaVersion = meta.getValue("schemaVersion").toInt(),
            fields = fields.sortedBy { it.position }
        )
    }

    fun recordCount(): Int {
        db.rawQuery("SELECT COUNT(*) FROM $RECORDS_TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * Runs aggregates declared on numeric fields. Returns map of
     * field display name → "<label> <value>" string (already formatted).
     */
    fun aggregateSummaries(def: DatabaseDef): List<String> {
        val agg = def.fields.filter { it.aggregate != null && it.type.isNumeric }
        if (agg.isEmpty()) return emptyList()
        val parts = agg.joinToString(", ") { f ->
            val col = f.columnName
            "${f.aggregate!!.sql}($col) AS ${col}_${f.aggregate.sql.lowercase()}"
        }
        db.rawQuery("SELECT $parts FROM $RECORDS_TABLE", null).use { c ->
            if (!c.moveToFirst()) return emptyList()
            return agg.mapIndexed { idx, f ->
                val raw = if (c.isNull(idx)) "—" else formatNumeric(c.getDouble(idx), f.type)
                "${f.displayName} · ${f.aggregate!!.label} $raw"
            }
        }
    }

    private fun formatNumeric(v: Double, type: FieldType): String =
        if (type == FieldType.INTEGER) v.toLong().toString()
        else {
            val rounded = (Math.round(v * 100.0) / 100.0)
            if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
            else rounded.toString()
        }

    // --- Record CRUD ----------------------------------------------------

    /**
     * Insert a new record. [values] is keyed by columnName; nulls and
     * missing keys are stored as NULL. Returns the new _id.
     */
    fun insertRecord(values: Map<String, Any?>): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("_created", now)
            put("_modified", now)
            values.forEach { (k, v) -> putAny(this, k, v) }
        }
        return db.insertOrThrow(RECORDS_TABLE, null, cv)
    }

    fun updateRecord(id: Long, values: Map<String, Any?>) {
        val cv = ContentValues().apply {
            put("_modified", System.currentTimeMillis())
            values.forEach { (k, v) -> putAny(this, k, v) }
        }
        db.update(RECORDS_TABLE, cv, "_id = ?", arrayOf(id.toString()))
    }

    fun deleteRecord(id: Long) {
        db.delete(RECORDS_TABLE, "_id = ?", arrayOf(id.toString()))
    }

    fun readRecord(id: Long, def: DatabaseDef): CurioRecord? {
        val cols = recordColumnList(def)
        db.rawQuery(
            "SELECT $cols FROM $RECORDS_TABLE WHERE _id = ?",
            arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.toRecord(def) else null
        }
    }

    fun listRecords(def: DatabaseDef, query: RecordQuery = RecordQuery()): List<CurioRecord> {
        val cols = recordColumnList(def)
        val (sql, args) = buildListSql(def, query, cols)
        val out = mutableListOf<CurioRecord>()
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += c.toRecord(def)
        }
        return out
    }

    private fun buildListSql(
        def: DatabaseDef,
        query: RecordQuery,
        cols: String
    ): Pair<String, List<String>> {
        val args = mutableListOf<String>()
        val wheres = mutableListOf<String>()
        val fieldsByCol = def.fields.associateBy { it.columnName }

        if (query.search.isNotBlank()) {
            val searchable = def.fields.filter {
                it.type == FieldType.TEXT ||
                    it.type == FieldType.ENUM ||
                    it.type == FieldType.AUTOCOMPLETE
            }
            if (searchable.isNotEmpty()) {
                val clause = searchable.joinToString(" OR ") {
                    "${it.columnName} LIKE ? ESCAPE '\\'"
                }
                wheres += "($clause)"
                val pattern = "%" + escapeLike(query.search) + "%"
                repeat(searchable.size) { args += pattern }
            }
        }

        for ((col, filter) in query.filters) {
            if (!filter.isActive) continue
            val field = fieldsByCol[col] ?: continue
            when (filter) {
                is FieldFilter.TextContains -> {
                    wheres += "$col LIKE ? ESCAPE '\\'"
                    args += "%" + escapeLike(filter.value) + "%"
                }
                is FieldFilter.NumericRange -> {
                    filter.min?.let { wheres += "$col >= ?"; args += it.toString() }
                    filter.max?.let { wheres += "$col <= ?"; args += it.toString() }
                }
                is FieldFilter.DateRange -> {
                    filter.fromMillis?.let { wheres += "$col >= ?"; args += it.toString() }
                    filter.toMillis?.let { wheres += "$col <= ?"; args += it.toString() }
                }
                is FieldFilter.BoolEquals -> {
                    wheres += "$col = ?"
                    args += if (filter.value) "1" else "0"
                }
                is FieldFilter.EnumIn -> {
                    if (filter.values.isNotEmpty()) {
                        val placeholders = filter.values.joinToString(",") { "?" }
                        wheres += "$col IN ($placeholders)"
                        args += filter.values
                    }
                }
            }
            // 'field' kept in scope so the lookup serves as validation
            // (unknown columns are silently skipped above).
            @Suppress("UNUSED_VARIABLE") val unused = field
        }

        val sortColumn = query.sort.columnName?.takeIf { fieldsByCol.containsKey(it) } ?: "_id"
        val direction = if (query.sort.descending) "DESC" else "ASC"
        val orderBy = if (sortColumn == "_id") {
            "_id $direction"
        } else {
            "$sortColumn $direction, _id DESC"
        }

        val sql = buildString {
            append("SELECT ").append(cols).append(" FROM ").append(RECORDS_TABLE)
            if (wheres.isNotEmpty()) {
                append(" WHERE ").append(wheres.joinToString(" AND "))
            }
            append(" ORDER BY ").append(orderBy)
        }
        return sql to args
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun recordColumnList(def: DatabaseDef): String {
        val base = "_id, _created, _modified"
        if (def.fields.isEmpty()) return base
        return base + def.fields.joinToString(prefix = ", ") { it.columnName }
    }

    private fun android.database.Cursor.toRecord(def: DatabaseDef): CurioRecord {
        val map = mutableMapOf<String, Any?>()
        def.fields.forEachIndexed { idx, f ->
            val col = idx + 3 // after _id, _created, _modified
            map[f.columnName] = if (isNull(col)) null else when (f.type) {
                FieldType.NUMBER -> getDouble(col)
                FieldType.INTEGER, FieldType.DATE, FieldType.BOOLEAN -> getLong(col)
                FieldType.TEXT, FieldType.IMAGE, FieldType.ENUM,
                FieldType.AUTOCOMPLETE, FieldType.CHECKLIST -> getString(col)
            }
        }
        return CurioRecord(
            id = getLong(0),
            createdAt = getLong(1),
            modifiedAt = getLong(2),
            values = map
        )
    }

    private fun putAny(cv: ContentValues, key: String, value: Any?) {
        when (value) {
            null -> cv.putNull(key)
            is String -> cv.put(key, value)
            is Int -> cv.put(key, value)
            is Long -> cv.put(key, value)
            is Double -> cv.put(key, value)
            is Float -> cv.put(key, value)
            is Boolean -> cv.put(key, if (value) 1 else 0)
            else -> cv.put(key, value.toString())
        }
    }

    // --- Schema mutations ----------------------------------------------

    /**
     * Distinct non-empty values for [columnName], used to power AUTOCOMPLETE
     * suggestions. Column name is derived deterministically from the
     * FieldDef.id (letters/digits only) so it is safe to interpolate.
     */
    fun distinctValues(columnName: String, limit: Int = 200): List<String> {
        requireSafeColumn(columnName)
        val out = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT $columnName FROM $RECORDS_TABLE " +
                "WHERE $columnName IS NOT NULL AND TRIM($columnName) <> '' " +
                "ORDER BY $columnName COLLATE NOCASE ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** Count of records whose value for [columnName] is non-null. */
    fun nonNullCount(columnName: String): Int {
        requireSafeColumn(columnName)
        db.rawQuery(
            "SELECT COUNT(*) FROM $RECORDS_TABLE WHERE $columnName IS NOT NULL", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Safe: SQLite always supports ALTER TABLE ADD COLUMN. */
    fun addField(field: FieldDef) {
        val nextPos = (readFields().maxOfOrNull { it.position } ?: -1) + 1
        val toInsert = field.copy(position = nextPos)
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE $RECORDS_TABLE ADD COLUMN ${toInsert.columnName} ${toInsert.type.sqliteAffinity}"
            )
            insertFieldRow(toInsert)
            touchModified()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Safe: changes display name / required / show-on-card / aggregate / enum options only. */
    fun updateFieldMetadata(field: FieldDef) {
        db.beginTransaction()
        try {
            db.execSQL(
                """UPDATE _fields SET display_name=?, required=?, show_on_card=?,
                                       aggregate=?, enum_options=?
                   WHERE id=?""",
                arrayOf(
                    field.displayName,
                    if (field.required) 1 else 0,
                    if (field.showOnCard) 1 else 0,
                    field.aggregate?.name,
                    Json.encodeToString(
                        ListSerializer(String.serializer()),
                        field.enumOptions
                    ),
                    field.id
                )
            )
            touchModified()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun reorderFields(orderedIds: List<String>) {
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { idx, id ->
                db.execSQL("UPDATE _fields SET position=? WHERE id=?", arrayOf(idx, id))
            }
            touchModified()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Destructive: drops the column via table-copy (SQLite 3.18 has no DROP COLUMN). */
    fun deleteField(fieldId: String) {
        val def = readDefinition()
        val remaining = def.fields.filter { it.id != fieldId }
        val mapping = remaining.associate { it.columnName to it.columnName }
        db.beginTransaction()
        try {
            rebuildRecordsTable(remaining, mapping)
            db.execSQL("DELETE FROM _fields WHERE id=?", arrayOf(fieldId))
            touchModified()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Destructive: table copy with SQLite CAST applied to the affected column.
     * Values that don't convert cleanly are coerced (text→int = 0 if not numeric;
     * 0/1 ↔ boolean; etc).
     */
    fun changeFieldType(fieldId: String, newType: FieldType) {
        val def = readDefinition()
        val target = def.fields.first { it.id == fieldId }
        if (target.type == newType) return
        val newFields = def.fields.map {
            if (it.id == fieldId) it.copy(type = newType) else it
        }
        val mapping = newFields.associate { f ->
            val expr = if (f.id == fieldId) {
                "CAST(${target.columnName} AS ${newType.sqliteAffinity})"
            } else f.columnName
            f.columnName to expr
        }
        db.beginTransaction()
        try {
            rebuildRecordsTable(newFields, mapping)
            db.execSQL(
                "UPDATE _fields SET type=? WHERE id=?",
                arrayOf(newType.name, fieldId)
            )
            touchModified()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun rebuildRecordsTable(
        newFields: List<FieldDef>,
        mapping: Map<String, String>
    ) {
        val ordered = newFields.sortedBy { it.position }
        val newColsSql = buildString {
            append("_id INTEGER PRIMARY KEY AUTOINCREMENT,")
            append("_created INTEGER NOT NULL,")
            append("_modified INTEGER NOT NULL")
            for (f in ordered) {
                append(", ").append(f.columnName).append(' ').append(f.type.sqliteAffinity)
            }
        }
        db.execSQL("CREATE TABLE ${RECORDS_TABLE}_new($newColsSql)")
        val targetCols = ordered.joinToString(", ") { it.columnName }
        val selectExprs = ordered.joinToString(", ") { mapping.getValue(it.columnName) }
        val targetList = if (targetCols.isEmpty()) "" else ", $targetCols"
        val selectList = if (selectExprs.isEmpty()) "" else ", $selectExprs"
        db.execSQL(
            "INSERT INTO ${RECORDS_TABLE}_new(_id, _created, _modified$targetList) " +
                "SELECT _id, _created, _modified$selectList FROM $RECORDS_TABLE"
        )
        db.execSQL("DROP TABLE $RECORDS_TABLE")
        db.execSQL("ALTER TABLE ${RECORDS_TABLE}_new RENAME TO $RECORDS_TABLE")
    }

    private fun touchModified() {
        putMeta("modifiedAt", System.currentTimeMillis().toString())
    }

    override fun close() {
        db.close()
    }

    // --- private helpers -------------------------------------------------

    private fun putMeta(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO _meta(key, value) VALUES(?, ?)", arrayOf(key, value))
    }

    private fun readAllMeta(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        db.rawQuery("SELECT key, value FROM _meta", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
        }
        return m
    }

    private fun insertFieldRow(field: FieldDef) {
        db.execSQL(
            """INSERT INTO _fields(
                   id, column_name, display_name, type, position,
                   required, show_on_card, aggregate, enum_options
               ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(
                field.id,
                field.columnName,
                field.displayName,
                field.type.name,
                field.position,
                if (field.required) 1 else 0,
                if (field.showOnCard) 1 else 0,
                field.aggregate?.name,
                json.encodeToString(ListSerializer(String.serializer()), field.enumOptions)
            )
        )
    }

    private fun readFields(): List<FieldDef> {
        val out = mutableListOf<FieldDef>()
        db.rawQuery(
            """SELECT id, column_name, display_name, type, position,
                      required, show_on_card, aggregate, enum_options
               FROM _fields""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += FieldDef(
                    id = c.getString(0),
                    columnName = c.getString(1),
                    displayName = c.getString(2),
                    type = FieldType.valueOf(c.getString(3)),
                    position = c.getInt(4),
                    required = c.getInt(5) == 1,
                    showOnCard = c.getInt(6) == 1,
                    aggregate = c.getString(7)?.let { AggregateOp.valueOf(it) },
                    enumOptions = json.decodeFromString(
                        ListSerializer(String.serializer()),
                        c.getString(8)
                    )
                )
            }
        }
        return out
    }

    private fun createRecordsTable(fields: List<FieldDef>, enforceNotNull: Boolean = true) {
        val cols = buildString {
            append("_id INTEGER PRIMARY KEY AUTOINCREMENT,")
            append("_created INTEGER NOT NULL,")
            append("_modified INTEGER NOT NULL")
            for (f in fields.sortedBy { it.position }) {
                append(", ")
                append(f.columnName).append(' ').append(f.type.sqliteAffinity)
                if (enforceNotNull && f.required) append(" NOT NULL")
            }
        }
        db.execSQL("CREATE TABLE $RECORDS_TABLE($cols)")
    }
}
