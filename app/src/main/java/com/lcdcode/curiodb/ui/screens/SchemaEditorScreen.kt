package com.lcdcode.curiodb.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.ui.SchemaEditorViewModel
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaEditorScreen(
    dbId: String,
    onBack: () -> Unit,
    vm: SchemaEditorViewModel = viewModel()
) {
    LaunchedEffect(dbId) { vm.load(dbId) }
    val state by vm.state.collectAsState()
    var editing by remember { mutableStateOf<FieldDef?>(null) }
    var pendingDelete by remember { mutableStateOf<FieldDef?>(null) }
    var pendingDeleteCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schema · ${state.def?.name.orEmpty()}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.def != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editing = FieldDef(
                            id = UUID.randomUUID().toString().replace("-", "").take(12),
                            columnName = "",
                            displayName = "",
                            type = com.lcdcode.curiodb.data.FieldType.TEXT,
                            position = state.def?.fields?.size ?: 0
                        )
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add field") }
                )
            }
        }
    ) { padding ->
        val def = state.def
        when {
            state.loading || def == null -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Loading…") }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (def.fields.isEmpty()) {
                    item {
                        Text(
                            "No fields yet. Add one with the button below.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(def.fields, key = { it.id }) { f ->
                    val idx = def.fields.indexOf(f)
                    FieldRow(
                        field = f,
                        canMoveUp = idx > 0,
                        canMoveDown = idx < def.fields.lastIndex,
                        onMoveUp = {
                            val ids = def.fields.map { it.id }.toMutableList()
                            ids.add(idx - 1, ids.removeAt(idx))
                            vm.reorder(ids)
                        },
                        onMoveDown = {
                            val ids = def.fields.map { it.id }.toMutableList()
                            ids.add(idx + 1, ids.removeAt(idx))
                            vm.reorder(ids)
                        },
                        onEdit = { editing = f },
                        onDelete = {
                            scope.launch {
                                pendingDeleteCount = vm.nonNullCount(f.columnName)
                                pendingDelete = f
                            }
                        }
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        val def = state.def
        val isExisting = def != null && def.fields.any { it.id == draft.id }
        val warning = if (isExisting) {
            "Changing the type will run a CAST on every row. Values that don't convert cleanly (e.g. text → number) will be coerced to 0 or empty. This cannot be undone."
        } else null
        FieldEditorSheet(
            draft = draft,
            isNew = !isExisting,
            typeChangeWarning = warning,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (!isExisting) {
                    vm.addField(updated)
                } else {
                    val previous = def!!.fields.first { it.id == updated.id }
                    vm.saveField(updated, previous.type)
                }
                editing = null
            }
        )
    }

    pendingDelete?.let { f ->
        DeleteFieldDialog(
            field = f,
            affectedRows = pendingDeleteCount,
            onCancel = { pendingDelete = null },
            onConfirm = {
                vm.deleteField(f.id)
                pendingDelete = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldRow(
    field: FieldDef,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(field.displayName, style = MaterialTheme.typography.bodyLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    AssistChip(onClick = {}, label = { Text(field.type.name) }, enabled = false)
                    if (field.required) AssistChip(onClick = {}, label = { Text("required") }, enabled = false)
                    if (field.showOnCard) AssistChip(onClick = {}, label = { Text("on card") }, enabled = false)
                    field.aggregate?.let {
                        AssistChip(onClick = {}, label = { Text(it.label) }, enabled = false)
                    }
                }
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun DeleteFieldDialog(
    field: FieldDef,
    affectedRows: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val matches = typed == field.displayName

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete field “${field.displayName}”?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (affectedRows > 0)
                        "$affectedRows record(s) currently have a non-null value for this field. They will lose that value permanently."
                    else
                        "No records have a value for this field, but the column will still be removed."
                )
                Text(
                    "To confirm, type the field name below.",
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text(field.displayName) }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = matches, onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
