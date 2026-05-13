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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.data.FieldType
import com.lcdcode.curiodb.ui.CreateDatabaseViewModel
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDatabaseScreen(
    onCancel: () -> Unit,
    onCreated: (String) -> Unit,
    vm: CreateDatabaseViewModel = viewModel()
) {
    var name by remember { mutableStateOf("") }
    val fields = remember { mutableStateListOf<FieldDef>() }
    var editing by remember { mutableStateOf<FieldDef?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canSave = name.isNotBlank() && !saving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New database") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            saving = true
                            scope.launch {
                                val id = vm.create(name, fields.toList())
                                onCreated(id)
                            }
                        }
                    ) { Text("Create") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Watch collection") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            Text(
                "Fields",
                style = MaterialTheme.typography.titleLarge
            )

            if (fields.isEmpty()) {
                Text(
                    "Add at least one field. You can reorder, edit, or delete fields below.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(fields, key = { it.id }) { f ->
                    FieldRow(
                        field = f,
                        canMoveUp = fields.indexOf(f) > 0,
                        canMoveDown = fields.indexOf(f) < fields.lastIndex,
                        onMoveUp = { fields.swap(fields.indexOf(f), fields.indexOf(f) - 1) },
                        onMoveDown = { fields.swap(fields.indexOf(f), fields.indexOf(f) + 1) },
                        onEdit = { editing = f },
                        onDelete = { fields.remove(f) }
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    editing = FieldDef(
                        id = UUID.randomUUID().toString().replace("-", "").take(12),
                        columnName = "",
                        displayName = "",
                        type = FieldType.TEXT,
                        position = fields.size
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  Add field")
            }
        }
    }

    editing?.let { draft ->
        FieldEditorSheet(
            draft = draft,
            isNew = fields.none { it.id == draft.id },
            onDismiss = { editing = null },
            onSave = { updated ->
                val existing = fields.indexOfFirst { it.id == updated.id }
                if (existing >= 0) fields[existing] = updated
                else fields.add(updated)
                editing = null
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
    Card(colors = CardDefaults.cardColors()) {
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

private fun <T> SnapshotStateList<T>.swap(i: Int, j: Int) {
    if (i == j || i !in indices || j !in indices) return
    val tmp = this[i]; this[i] = this[j]; this[j] = tmp
}
