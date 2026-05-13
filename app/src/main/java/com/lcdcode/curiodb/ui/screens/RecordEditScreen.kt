package com.lcdcode.curiodb.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.data.FieldType
import com.lcdcode.curiodb.ui.RecordEditViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditScreen(
    dbId: String,
    recordId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: RecordEditViewModel = viewModel()
) {
    LaunchedEffect(dbId, recordId) { vm.load(dbId, recordId) }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (recordId == null) "New record" else "Edit record") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = !saving && state.def != null,
                        onClick = {
                            saving = true
                            scope.launch {
                                val result = runCatching { vm.save(dbId, recordId) }
                                result.fold(
                                    onSuccess = { onSaved() },
                                    onFailure = {
                                        saving = false
                                        snackbar.showSnackbar(
                                            it.message ?: "Could not save record."
                                        )
                                    }
                                )
                            }
                        }
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        val def = state.def
        if (state.loading || def == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) { Text("Loading…") }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.padding(top = 12.dp))
                def.fields.forEach { field ->
                    FieldInput(
                        field = field,
                        value = state.values[field.columnName],
                        onChange = { vm.updateValue(field.columnName, it) },
                        dbId = dbId,
                        imagesDir = vm.imagesDir(dbId),
                        vm = vm
                    )
                }
                Box(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(
    field: FieldDef,
    value: Any?,
    onChange: (Any?) -> Unit,
    dbId: String,
    imagesDir: File,
    vm: RecordEditViewModel
) {
    val label = field.displayName + if (field.required) " *" else ""
    when (field.type) {
        FieldType.TEXT -> OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { onChange(it) },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        FieldType.INTEGER -> OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { s -> onChange(s.toLongOrNull()) },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth()
        )
        FieldType.NUMBER -> OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { s -> onChange(s.toDoubleOrNull()) },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            modifier = Modifier.fillMaxWidth()
        )
        FieldType.DATE -> DateField(label, value as? Long, onChange)
        FieldType.BOOLEAN -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(
                checked = (value as? Long) == 1L,
                onCheckedChange = { onChange(if (it) 1L else 0L) }
            )
        }
        FieldType.ENUM -> EnumField(label, field.enumOptions, value as? String, onChange)
        FieldType.IMAGE -> ImageField(label, value as? String, imagesDir, onChange)
        FieldType.AUTOCOMPLETE -> AutocompleteField(
            label = label,
            current = value as? String,
            dbId = dbId,
            columnName = field.columnName,
            vm = vm,
            onChange = onChange
        )
        FieldType.CHECKLIST -> ChecklistField(
            label = label,
            options = field.enumOptions,
            storedJson = value as? String,
            onChange = onChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutocompleteField(
    label: String,
    current: String?,
    dbId: String,
    columnName: String,
    vm: RecordEditViewModel,
    onChange: (Any?) -> Unit
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(dbId, columnName) {
        suggestions = vm.suggestions(dbId, columnName)
    }

    val filtered = remember(text, suggestions) {
        if (text.isBlank()) suggestions
        else suggestions.filter { it.contains(text, ignoreCase = true) && it != text }
    }

    Box {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it.ifBlank { null })
                menuOpen = filtered.isNotEmpty()
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    TextButton(onClick = { menuOpen = !menuOpen }) {
                        Text(if (menuOpen) "Hide" else "Suggest")
                    }
                }
            }
        )
        DropdownMenu(
            expanded = menuOpen && filtered.isNotEmpty(),
            onDismissRequest = { menuOpen = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false)
        ) {
            filtered.take(20).forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        text = opt
                        onChange(opt)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

private val checklistJson = Json { ignoreUnknownKeys = true }
private val checklistSerializer = ListSerializer(String.serializer())

@Composable
private fun ChecklistField(
    label: String,
    options: List<String>,
    storedJson: String?,
    onChange: (Any?) -> Unit
) {
    val checked = remember(storedJson) {
        if (storedJson.isNullOrBlank()) emptySet()
        else runCatching {
            checklistJson.decodeFromString(checklistSerializer, storedJson).toSet()
        }.getOrDefault(emptySet())
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (options.isEmpty()) {
            Text(
                "No options defined in schema.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        options.forEach { opt ->
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = opt in checked,
                    onCheckedChange = { isOn ->
                        val next = if (isOn) checked + opt else checked - opt
                        val ordered = options.filter { it in next }
                        val encoded =
                            if (ordered.isEmpty()) null
                            else checklistJson.encodeToString(checklistSerializer, ordered)
                        onChange(encoded)
                    }
                )
                Text(opt)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, current: Long?, onChange: (Any?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val formatted = current?.let {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(it))
    }.orEmpty()

    OutlinedTextField(
        value = formatted,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            Row {
                TextButton(onClick = { showPicker = true }) { Text("Pick") }
                if (current != null) {
                    TextButton(onClick = { onChange(null) }) { Text("Clear") }
                }
            }
        }
    )
    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = current)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(pickerState.selectedDateMillis)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumField(
    label: String,
    options: List<String>,
    current: String?,
    onChange: (Any?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = current.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { open = true }) { Text("Select") }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("—") }, onClick = { onChange(null); open = false })
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); open = false })
            }
        }
    }
}

@Composable
private fun ImageField(
    label: String,
    current: String?,
    imagesDir: File,
    onChange: (Any?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val filename = withContext(Dispatchers.IO) {
                    val out = File(imagesDir, "${UUID.randomUUID()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    out.name
                }
                onChange(filename)
            }
        }
    }

    var viewerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (!current.isNullOrBlank()) {
            AsyncImage(
                model = File(imagesDir, current),
                contentDescription = null,
                modifier = Modifier
                    .size(160.dp)
                    .clickable { viewerOpen = true }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text("  Choose image")
            }
            if (!current.isNullOrBlank()) {
                TextButton(onClick = { onChange(null) }) { Text("Remove") }
            }
        }
    }

    if (viewerOpen && !current.isNullOrBlank()) {
        FullScreenImageViewer(
            file = File(imagesDir, current),
            onDismiss = { viewerOpen = false }
        )
    }
}

@Composable
private fun FullScreenImageViewer(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() }
        ) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
            )
        }
    }
}
