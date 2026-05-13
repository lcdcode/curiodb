package com.lcdcode.curiodb.data

/**
 * A row in a database's records table.
 *
 * [values] is keyed by the field's columnName. Value runtime types follow
 * [FieldType]:
 *   TEXT, IMAGE, ENUM -> String
 *   INTEGER, DATE, BOOLEAN -> Long  (BOOLEAN: 0/1; DATE: epoch millis)
 *   NUMBER -> Double
 *   null -> null
 */
data class CurioRecord(
    val id: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val values: Map<String, Any?>
)
