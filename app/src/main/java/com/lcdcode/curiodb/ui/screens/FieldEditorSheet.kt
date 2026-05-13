package com.lcdcode.curiodb.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lcdcode.curiodb.data.AggregateOp
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.data.FieldType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldEditorSheet(
    draft: FieldDef,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (FieldDef) -> Unit,
    typeChangeWarning: String? = null
) {
    var displayName by remember { mutableStateOf(draft.displayName) }
    var type by remember { mutableStateOf(draft.type) }
    var required by remember { mutableStateOf(draft.required) }
    var showOnCard by remember { mutableStateOf(draft.showOnCard) }
    var aggregate by remember { mutableStateOf(draft.aggregate) }
    var enumOptionsText by remember { mutableStateOf(draft.enumOptions.joinToString(", ")) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    val needsOptions = type == FieldType.ENUM || type == FieldType.CHECKLIST
    val canSave = displayName.isNotBlank() &&
        (!needsOptions || enumOptionsText.split(",").any { it.isNotBlank() })

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (isNew) "New field" else "Edit field",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Box {
                OutlinedTextField(
                    value = type.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = { typeMenuOpen = true }) { Text("Change") }
                    }
                )
                DropdownMenu(
                    expanded = typeMenuOpen,
                    onDismissRequest = { typeMenuOpen = false }
                ) {
                    FieldType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.name) },
                            onClick = {
                                type = t
                                if (!t.isNumeric) aggregate = null
                                typeMenuOpen = false
                            }
                        )
                    }
                }
            }

            if (!isNew && type != draft.type && typeChangeWarning != null) {
                Text(
                    typeChangeWarning,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (needsOptions) {
                OutlinedTextField(
                    value = enumOptionsText,
                    onValueChange = { enumOptionsText = it },
                    label = { Text("Options (comma separated)") },
                    placeholder = { Text("New, In progress, Done") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Required", modifier = Modifier.weight(1f))
                Switch(checked = required, onCheckedChange = { required = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show on card", modifier = Modifier.weight(1f))
                Switch(checked = showOnCard, onCheckedChange = { showOnCard = it })
            }

            if (type.isNumeric) {
                Text("Aggregate on home", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = aggregate == null,
                        onClick = { aggregate = null },
                        label = { Text("None") }
                    )
                    AggregateOp.entries.forEach { op ->
                        FilterChip(
                            selected = aggregate == op,
                            onClick = { aggregate = op },
                            label = { Text(op.name.lowercase()) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = canSave,
                    onClick = {
                        onSave(
                            draft.copy(
                                displayName = displayName.trim(),
                                type = type,
                                required = required,
                                showOnCard = showOnCard,
                                aggregate = aggregate,
                                enumOptions = if (needsOptions)
                                    enumOptionsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                else emptyList()
                            )
                        )
                    }
                ) { Text(if (isNew) "Add" else "Save") }
            }
        }
    }
}
