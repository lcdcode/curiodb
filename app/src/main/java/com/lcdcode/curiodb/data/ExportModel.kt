package com.lcdcode.curiodb.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire format for .curiodb.json export files.
 *
 * Images are base64-encoded by filename so a single .json file is fully
 * portable across devices.
 */
@Serializable
data class CurioExport(
    val format: String = "curiodb",
    val version: Int = 1,
    val database: ExportDatabase,
    val records: List<ExportRecord>,
    val images: Map<String, String> = emptyMap()
)

@Serializable
data class ExportDatabase(
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val schemaVersion: Int,
    val fields: List<ExportField>
)

@Serializable
data class ExportField(
    val id: String,
    val columnName: String,
    val displayName: String,
    val type: FieldType,
    val position: Int,
    val required: Boolean,
    val showOnCard: Boolean,
    val aggregate: AggregateOp? = null,
    val enumOptions: List<String> = emptyList()
)

@Serializable
data class ExportRecord(
    val createdAt: Long,
    val modifiedAt: Long,
    /** Keys are columnName. Values use JSON-native types (string/number/boolean/null). */
    val values: Map<String, JsonElement>
)
