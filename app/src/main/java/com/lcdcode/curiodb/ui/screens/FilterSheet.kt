package com.lcdcode.curiodb.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lcdcode.curiodb.data.DatabaseDef
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.data.FieldFilter
import com.lcdcode.curiodb.data.FieldType
import com.lcdcode.curiodb.data.RecordQuery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    def: DatabaseDef,
    query: RecordQuery,
    onDismiss: () -> Unit,
    onApply: (RecordQuery) -> Unit
) {
    var draft by remember { mutableStateOf(query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)

            if (def.fields.isEmpty()) {
                Text(
                    "This database has no fields to filter on.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            def.fields.forEach { f ->
                val current = draft.filters[f.columnName]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.displayName, style = MaterialTheme.typography.bodyLarge)
                    FilterWidget(
                        field = f,
                        current = current,
                        onChange = { updated ->
                            val map = draft.filters.toMutableMap()
                            if (updated == null) map.remove(f.columnName)
                            else map[f.columnName] = updated
                            draft = draft.copy(filters = map)
                        }
                    )
                }
                HorizontalDivider()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 0.dp)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { draft = draft.copy(filters = emptyMap()) }) {
                    Text("Clear all")
                }
                Button(onClick = { onApply(draft) }) { Text("Apply") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterWidget(
    field: FieldDef,
    current: FieldFilter?,
    onChange: (FieldFilter?) -> Unit
) {
    when (field.type) {
        FieldType.TEXT, FieldType.AUTOCOMPLETE -> {
            val v = (current as? FieldFilter.TextContains)?.value.orEmpty()
            OutlinedTextField(
                value = v,
                onValueChange = {
                    onChange(if (it.isBlank()) null else FieldFilter.TextContains(it))
                },
                placeholder = { Text("Contains…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        FieldType.INTEGER, FieldType.NUMBER -> {
            val r = current as? FieldFilter.NumericRange ?: FieldFilter.NumericRange()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = r.min?.toCleanString().orEmpty(),
                    onValueChange = { s ->
                        val parsed = s.toDoubleOrNull()
                        val next = r.copy(min = parsed)
                        onChange(if (next.isActive) next else null)
                    },
                    placeholder = { Text("Min") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (field.type == FieldType.INTEGER) KeyboardType.Number else KeyboardType.Decimal
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = r.max?.toCleanString().orEmpty(),
                    onValueChange = { s ->
                        val parsed = s.toDoubleOrNull()
                        val next = r.copy(max = parsed)
                        onChange(if (next.isActive) next else null)
                    },
                    placeholder = { Text("Max") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (field.type == FieldType.INTEGER) KeyboardType.Number else KeyboardType.Decimal
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        FieldType.DATE -> {
            val r = current as? FieldFilter.DateRange ?: FieldFilter.DateRange()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DateBoundButton("From", r.fromMillis) {
                    val next = r.copy(fromMillis = it)
                    onChange(if (next.isActive) next else null)
                }
                DateBoundButton("To", r.toMillis) {
                    val next = r.copy(toMillis = it)
                    onChange(if (next.isActive) next else null)
                }
            }
        }
        FieldType.BOOLEAN -> {
            val v = (current as? FieldFilter.BoolEquals)?.value
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = v == null,
                    onClick = { onChange(null) },
                    label = { Text("Any") }
                )
                FilterChip(
                    selected = v == true,
                    onClick = { onChange(FieldFilter.BoolEquals(true)) },
                    label = { Text("Yes") }
                )
                FilterChip(
                    selected = v == false,
                    onClick = { onChange(FieldFilter.BoolEquals(false)) },
                    label = { Text("No") }
                )
            }
        }
        FieldType.ENUM -> {
            val selected = (current as? FieldFilter.EnumIn)?.values.orEmpty()
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                field.enumOptions.forEach { opt ->
                    FilterChip(
                        selected = opt in selected,
                        onClick = {
                            val next = if (opt in selected) selected - opt else selected + opt
                            onChange(if (next.isEmpty()) null else FieldFilter.EnumIn(next))
                        },
                        label = { Text(opt) }
                    )
                }
            }
        }
        FieldType.IMAGE -> {
            Text(
                "Image fields can't be filtered.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FieldType.CHECKLIST -> {
            Text(
                "Checklist fields can't be filtered.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateBoundButton(
    label: String,
    current: Long?,
    onChange: (Long?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val formatted = current?.let {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(it))
    } ?: label

    TextButton(onClick = { open = true }) { Text(formatted) }
    if (current != null) {
        TextButton(onClick = { onChange(null) }) { Text("×") }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = current)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.selectedDateMillis)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = state) }
    }
}

private fun Double.toCleanString(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()
