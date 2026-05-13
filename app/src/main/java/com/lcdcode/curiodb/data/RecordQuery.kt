package com.lcdcode.curiodb.data

/**
 * Captures the user-driven query state for a single database view:
 * search text, sort specification, and per-field filters (keyed by column name).
 */
data class RecordQuery(
    val search: String = "",
    val sort: SortSpec = SortSpec(),
    val filters: Map<String, FieldFilter> = emptyMap()
) {
    val isActive: Boolean
        get() = search.isNotBlank() || sort.columnName != null ||
            filters.values.any { it.isActive }

    val activeFilterCount: Int
        get() = filters.values.count { it.isActive }
}

/**
 * [columnName] = null means the implicit default of newest-first (_id DESC).
 */
data class SortSpec(
    val columnName: String? = null,
    val descending: Boolean = true
)

sealed interface FieldFilter {
    val isActive: Boolean

    data class TextContains(val value: String) : FieldFilter {
        override val isActive get() = value.isNotBlank()
    }

    data class NumericRange(val min: Double? = null, val max: Double? = null) : FieldFilter {
        override val isActive get() = min != null || max != null
    }

    data class DateRange(val fromMillis: Long? = null, val toMillis: Long? = null) : FieldFilter {
        override val isActive get() = fromMillis != null || toMillis != null
    }

    data class BoolEquals(val value: Boolean) : FieldFilter {
        override val isActive get() = true
    }

    data class EnumIn(val values: Set<String> = emptySet()) : FieldFilter {
        override val isActive get() = values.isNotEmpty()
    }
}
