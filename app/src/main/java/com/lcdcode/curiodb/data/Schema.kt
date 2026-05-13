package com.lcdcode.curiodb.data

import kotlinx.serialization.Serializable

/**
 * Definition of a single field within a database's schema.
 *
 * - [columnName] is the physical SQLite column name (sanitized; ASCII identifier).
 * - [displayName] is what the user sees and edits.
 * - [aggregate] is non-null only for numeric types and drives home-card aggregates.
 * - [enumOptions] is populated only when [type] is [FieldType.ENUM].
 */
@Serializable
data class FieldDef(
    val id: String,
    val columnName: String,
    val displayName: String,
    val type: FieldType,
    val position: Int,
    val required: Boolean = false,
    val showOnCard: Boolean = false,
    val aggregate: AggregateOp? = null,
    val enumOptions: List<String> = emptyList()
)

@Serializable
data class DatabaseDef(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val schemaVersion: Int = 1,
    val fields: List<FieldDef> = emptyList()
)
