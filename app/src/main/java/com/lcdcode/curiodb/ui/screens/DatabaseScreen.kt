package com.lcdcode.curiodb.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.curiodb.data.CurioRecord
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.data.FieldType
import com.lcdcode.curiodb.data.SortSpec
import com.lcdcode.curiodb.ui.DatabaseViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DatabaseScreen(
    dbId: String,
    onBack: () -> Unit,
    onAddRecord: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onEditSchema: () -> Unit,
    vm: DatabaseViewModel = viewModel()
) {
    LaunchedEffect(dbId) { vm.load(dbId) }
    val state by vm.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<CurioRecord?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var pendingIncludeImages by remember { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val includeImages = pendingIncludeImages
            scope.launch {
                val result = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        vm.export(dbId, it, includeImages)
                    } ?: error("Could not open destination.")
                }
                result.fold(
                    onSuccess = { snackbar.showSnackbar("Exported.") },
                    onFailure = { snackbar.showSnackbar("Export failed: ${it.message}") }
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (searchActive) {
                SearchBar(
                    initial = state.query.search,
                    onClose = {
                        searchActive = false
                        vm.updateQuery { it.copy(search = "") }
                    },
                    onChange = { s -> vm.updateQuery { it.copy(search = s) } }
                )
            } else {
                TopAppBar(
                    title = { Text(state.def?.name ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        BadgedBox(
                            badge = {
                                val count = state.query.activeFilterCount
                                if (count > 0) Badge { Text(count.toString()) }
                            }
                        ) {
                            IconButton(onClick = { filterSheetOpen = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter")
                            }
                        }
                        IconButton(onClick = onEditSchema) {
                            Icon(Icons.Default.Tune, contentDescription = "Edit schema")
                        }
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export…") },
                                leadingIcon = {
                                    Icon(Icons.Default.FileUpload, contentDescription = null)
                                },
                                onClick = {
                                    overflowOpen = false
                                    exportDialogOpen = true
                                }
                            )
                        }
                        SortMenu(
                            expanded = sortMenuOpen,
                            fields = state.def?.fields.orEmpty(),
                            current = state.query.sort,
                            onDismiss = { sortMenuOpen = false },
                            onPick = { spec ->
                                vm.updateQuery { it.copy(sort = spec) }
                                sortMenuOpen = false
                            }
                        )
                    }
                )
            }
        },
        floatingActionButton = {
            if (state.def != null) {
                ExtendedFloatingActionButton(
                    onClick = onAddRecord,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add record") }
                )
            }
        }
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Loading…") }

            state.records.isEmpty() -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.query.isActive) "No records match the current query."
                    else "No records yet. Tap “Add record”.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                val def = state.def!!
                val displayFields = def.fields.filter { it.showOnCard }
                    .ifEmpty { def.fields.take(2) }

                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.records, key = { it.id }) { rec ->
                        RecordCard(
                            record = rec,
                            fields = displayFields,
                            imagesDir = vm.imagesDir(dbId),
                            onClick = { onOpenRecord(rec.id) },
                            onLongClick = { pendingDelete = rec }
                        )
                    }
                }
            }
        }
    }

    if (exportDialogOpen) {
        AlertDialog(
            onDismissRequest = { exportDialogOpen = false },
            title = { Text("Export database") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include images", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = pendingIncludeImages,
                        onCheckedChange = { pendingIncludeImages = it }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportDialogOpen = false
                    val safeName = (state.def?.name ?: "database")
                        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
                        .ifBlank { "database" }
                    val suffix = if (pendingIncludeImages) "" else ".no-images"
                    exportLauncher.launch("$safeName$suffix.curiodb.zip")
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { exportDialogOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (filterSheetOpen) {
        state.def?.let { def ->
            FilterSheet(
                def = def,
                query = state.query,
                onDismiss = { filterSheetOpen = false },
                onApply = { updated ->
                    vm.updateQuery { updated }
                    filterSheetOpen = false
                }
            )
        }
    }

    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete record?") },
            text = { Text("This record will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRecord(dbId, rec.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    initial: String,
    onClose: () -> Unit,
    onChange: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    TopAppBar(
        title = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onChange(it) },
                placeholder = { Text("Search…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        }
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    fields: List<FieldDef>,
    current: SortSpec,
    onDismiss: () -> Unit,
    onPick: (SortSpec) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Newest first (default)") },
            trailingIcon = {
                if (current.columnName == null && current.descending)
                    Icon(Icons.Default.Check, contentDescription = null)
            },
            onClick = { onPick(SortSpec(columnName = null, descending = true)) }
        )
        DropdownMenuItem(
            text = { Text("Oldest first") },
            trailingIcon = {
                if (current.columnName == null && !current.descending)
                    Icon(Icons.Default.Check, contentDescription = null)
            },
            onClick = { onPick(SortSpec(columnName = null, descending = false)) }
        )
        if (fields.isNotEmpty()) HorizontalDivider()
        fields.forEach { f ->
            DropdownMenuItem(
                text = { Text("${f.displayName} ↑") },
                trailingIcon = {
                    if (current.columnName == f.columnName && !current.descending)
                        Icon(Icons.Default.Check, contentDescription = null)
                },
                onClick = { onPick(SortSpec(columnName = f.columnName, descending = false)) }
            )
            DropdownMenuItem(
                text = { Text("${f.displayName} ↓") },
                trailingIcon = {
                    if (current.columnName == f.columnName && current.descending)
                        Icon(Icons.Default.Check, contentDescription = null)
                },
                onClick = { onPick(SortSpec(columnName = f.columnName, descending = true)) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(
    record: CurioRecord,
    fields: List<FieldDef>,
    imagesDir: File,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val imageFields = fields.filter { it.type == FieldType.IMAGE }
    val textFields = fields.filter { it.type != FieldType.IMAGE }

    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (textFields.isEmpty() && imageFields.isEmpty()) {
                    Text(
                        "Record #${record.id}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    textFields.forEachIndexed { idx, f ->
                        val raw = record.values[f.columnName]
                        val text = formatValue(raw, f)
                        Text(
                            text = if (idx == 0) text else "${f.displayName}: $text",
                            style = if (idx == 0) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.bodyLarge,
                            color = if (idx == 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (textFields.isEmpty()) {
                        Text(
                            "Record #${record.id}",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
            if (imageFields.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageFields.forEach { f ->
                        val name = record.values[f.columnName] as? String
                        if (!name.isNullOrBlank()) {
                            AsyncImage(
                                model = File(imagesDir, name),
                                contentDescription = f.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
private val checklistListSerializer = ListSerializer(String.serializer())

internal fun formatValue(raw: Any?, field: FieldDef): String {
    if (raw == null) return "—"
    return when (field.type) {
        FieldType.DATE -> dateFormatter.format(Date(raw as Long))
        FieldType.BOOLEAN -> if ((raw as Long) == 1L) "Yes" else "No"
        FieldType.NUMBER -> {
            val v = (raw as Double)
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        }
        FieldType.IMAGE -> if ((raw as String).isBlank()) "—" else "📷"
        FieldType.CHECKLIST -> {
            val s = raw as? String ?: return "—"
            if (s.isBlank()) "—"
            else runCatching {
                val items = kotlinx.serialization.json.Json
                    .decodeFromString(checklistListSerializer, s)
                if (items.isEmpty()) "—"
                else if (items.size <= 3) items.joinToString(", ")
                else items.take(3).joinToString(", ") + " +${items.size - 3}"
            }.getOrDefault("—")
        }
        else -> raw.toString()
    }
}
