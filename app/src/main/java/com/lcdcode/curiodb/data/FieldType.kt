package com.lcdcode.curiodb.data

import kotlinx.serialization.Serializable

/**
 * The set of user-selectable field types for a CurioDB schema.
 *
 * Each maps to an underlying SQLite affinity. IMAGE stores a relative path
 * into the database's sibling images folder; the file itself is managed
 * by [CurioFileManager].
 */
@Serializable
enum class FieldType(val sqliteAffinity: String, val isNumeric: Boolean) {
    TEXT(sqliteAffinity = "TEXT", isNumeric = false),
    INTEGER(sqliteAffinity = "INTEGER", isNumeric = true),
    NUMBER(sqliteAffinity = "REAL", isNumeric = true),
    DATE(sqliteAffinity = "INTEGER", isNumeric = false),     // stored as epoch millis
    BOOLEAN(sqliteAffinity = "INTEGER", isNumeric = false),  // 0/1
    IMAGE(sqliteAffinity = "TEXT", isNumeric = false),       // relative filename in images/
    ENUM(sqliteAffinity = "TEXT", isNumeric = false),
    AUTOCOMPLETE(sqliteAffinity = "TEXT", isNumeric = false),// free-text with prior-value suggestions
    CHECKLIST(sqliteAffinity = "TEXT", isNumeric = false)    // JSON array of checked option labels
}

@Serializable
enum class AggregateOp(val sql: String, val label: String) {
    SUM("SUM", "Σ"),
    AVG("AVG", "avg"),
    MIN("MIN", "min"),
    MAX("MAX", "max"),
    COUNT("COUNT", "count")
}
