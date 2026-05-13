package com.lcdcode.curiodb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lcdcode.curiodb.CurioApp
import com.lcdcode.curiodb.data.CurioRecord
import com.lcdcode.curiodb.data.CurioRepository
import com.lcdcode.curiodb.data.DatabaseDef
import com.lcdcode.curiodb.data.DatabaseSummary
import com.lcdcode.curiodb.data.FieldDef
import com.lcdcode.curiodb.data.RecordQuery
import com.lcdcode.curiodb.data.SortPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: CurioRepository = (app as CurioApp).repository
    private val sortPrefs: SortPreferences = (app as CurioApp).sortPreferences
    val databases: StateFlow<List<DatabaseSummary>> = repo.summaries

    init {
        viewModelScope.launch { repo.refresh() }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.deleteDatabase(id)
            sortPrefs.clear(id)
        }
    }

    suspend fun import(input: InputStream): CurioRepository.ImportResult =
        repo.importFromStream(input)
}

class CreateDatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: CurioRepository = (app as CurioApp).repository

    suspend fun create(name: String, fields: List<FieldDef>): String =
        repo.createDatabase(name, fields)
}

data class DatabaseScreenState(
    val def: DatabaseDef? = null,
    val records: List<CurioRecord> = emptyList(),
    val query: RecordQuery = RecordQuery(),
    val loading: Boolean = true
)

class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: CurioRepository = (app as CurioApp).repository
    private val sortPrefs: SortPreferences = (app as CurioApp).sortPreferences
    private val _state = MutableStateFlow(DatabaseScreenState())
    val state: StateFlow<DatabaseScreenState> = _state.asStateFlow()
    private var currentDbId: String? = null

    fun load(dbId: String) {
        currentDbId = dbId
        val restoredQuery = _state.value.query.copy(sort = sortPrefs.load(dbId))
        _state.value = _state.value.copy(query = restoredQuery)
        viewModelScope.launch {
            val (def, records) = repo.listRecords(dbId, restoredQuery)
            _state.value = _state.value.copy(
                def = def,
                records = records,
                loading = false
            )
        }
    }

    fun updateQuery(transform: (RecordQuery) -> RecordQuery) {
        val prev = _state.value.query
        val next = transform(prev)
        _state.value = _state.value.copy(query = next)
        if (next.sort != prev.sort) {
            currentDbId?.let { sortPrefs.save(it, next.sort) }
        }
        currentDbId?.let { reload(it) }
    }

    private fun reload(dbId: String) {
        viewModelScope.launch {
            val (def, records) = repo.listRecords(dbId, _state.value.query)
            _state.value = _state.value.copy(def = def, records = records)
        }
    }

    fun deleteRecord(dbId: String, recordId: Long) {
        viewModelScope.launch {
            repo.deleteRecord(dbId, recordId)
            reload(dbId)
        }
    }

    suspend fun export(dbId: String, output: OutputStream, includeImages: Boolean) {
        repo.exportToStream(dbId, output, includeImages)
    }

    fun imagesDir(dbId: String) = repo.imagesDir(dbId)
}

data class RecordEditState(
    val def: DatabaseDef? = null,
    val values: Map<String, Any?> = emptyMap(),
    val loading: Boolean = true
)

data class SchemaEditorState(
    val def: DatabaseDef? = null,
    val loading: Boolean = true
)

class SchemaEditorViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: CurioRepository = (app as CurioApp).repository
    private val _state = MutableStateFlow(SchemaEditorState())
    val state: StateFlow<SchemaEditorState> = _state.asStateFlow()
    private var dbId: String? = null

    fun load(id: String) {
        dbId = id
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val id = dbId ?: return
        val def = repo.readDefinition(id)
        _state.value = SchemaEditorState(def = def, loading = false)
    }

    fun addField(field: com.lcdcode.curiodb.data.FieldDef) {
        viewModelScope.launch { dbId?.let { repo.addField(it, field); reload() } }
    }

    fun updateFieldMetadata(field: com.lcdcode.curiodb.data.FieldDef) {
        viewModelScope.launch { dbId?.let { repo.updateFieldMetadata(it, field); reload() } }
    }

    fun changeType(fieldId: String, newType: com.lcdcode.curiodb.data.FieldType) {
        viewModelScope.launch { dbId?.let { repo.changeFieldType(it, fieldId, newType); reload() } }
    }

    fun saveField(updated: com.lcdcode.curiodb.data.FieldDef, oldType: com.lcdcode.curiodb.data.FieldType?) {
        viewModelScope.launch {
            dbId?.let { repo.updateFieldFull(it, updated, oldType); reload() }
        }
    }

    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch { dbId?.let { repo.reorderFields(it, orderedIds); reload() } }
    }

    fun deleteField(fieldId: String) {
        viewModelScope.launch { dbId?.let { repo.deleteField(it, fieldId); reload() } }
    }

    suspend fun nonNullCount(columnName: String): Int =
        dbId?.let { repo.nonNullCount(it, columnName) } ?: 0
}

class RecordEditViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: CurioRepository = (app as CurioApp).repository
    private val _state = MutableStateFlow(RecordEditState())
    val state: StateFlow<RecordEditState> = _state.asStateFlow()

    fun load(dbId: String, recordId: Long?) {
        viewModelScope.launch {
            if (recordId == null) {
                val def = repo.readDefinition(dbId)
                val defaults = def.fields
                    .filter { it.type == com.lcdcode.curiodb.data.FieldType.BOOLEAN }
                    .associate { it.columnName to 0L as Any? }
                _state.value = RecordEditState(def = def, values = defaults, loading = false)
            } else {
                val (def, rec) = repo.readRecord(dbId, recordId)
                _state.value = RecordEditState(
                    def = def,
                    values = rec?.values.orEmpty(),
                    loading = false
                )
            }
        }
    }

    fun updateValue(columnName: String, value: Any?) {
        _state.value = _state.value.copy(values = _state.value.values + (columnName to value))
    }

    suspend fun save(dbId: String, recordId: Long?): Long =
        repo.saveRecord(dbId, recordId, _state.value.values)

    fun imagesDir(dbId: String) = repo.imagesDir(dbId)

    suspend fun suggestions(dbId: String, columnName: String): List<String> =
        repo.distinctValues(dbId, columnName)
}

