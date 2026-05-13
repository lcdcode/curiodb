package com.lcdcode.curiodb

import android.app.Application
import com.lcdcode.curiodb.data.CurioRepository
import com.lcdcode.curiodb.data.SortPreferences

class CurioApp : Application() {
    val repository: CurioRepository by lazy { CurioRepository(this) }
    val sortPreferences: SortPreferences by lazy { SortPreferences(this) }
}
