package com.lcdcode.curiodb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lcdcode.curiodb.ui.screens.CreateDatabaseScreen
import com.lcdcode.curiodb.ui.screens.DatabaseScreen
import com.lcdcode.curiodb.ui.screens.HomeScreen
import com.lcdcode.curiodb.ui.screens.RecordEditScreen
import com.lcdcode.curiodb.ui.screens.SchemaEditorScreen
import com.lcdcode.curiodb.ui.theme.CurioDBTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CurioDBTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CurioNavHost()
                }
            }
        }
    }
}

@Composable
private fun CurioNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenDatabase = { dbId -> nav.navigate("db/$dbId") },
                onCreateDatabase = { nav.navigate("create") }
            )
        }
        composable("create") {
            CreateDatabaseScreen(
                onCancel = { nav.popBackStack() },
                onCreated = { _ -> nav.popBackStack() }
            )
        }
        composable(
            route = "db/{dbId}",
            arguments = listOf(navArgument("dbId") { type = NavType.StringType })
        ) { entry ->
            val dbId = entry.arguments?.getString("dbId").orEmpty()
            DatabaseScreen(
                dbId = dbId,
                onBack = { nav.popBackStack() },
                onAddRecord = { nav.navigate("db/$dbId/record/new") },
                onOpenRecord = { rid -> nav.navigate("db/$dbId/record/$rid") },
                onEditSchema = { nav.navigate("db/$dbId/schema") }
            )
        }
        composable(
            route = "db/{dbId}/schema",
            arguments = listOf(navArgument("dbId") { type = NavType.StringType })
        ) { entry ->
            val dbId = entry.arguments?.getString("dbId").orEmpty()
            SchemaEditorScreen(
                dbId = dbId,
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = "db/{dbId}/record/{recordId}",
            arguments = listOf(
                navArgument("dbId") { type = NavType.StringType },
                navArgument("recordId") { type = NavType.StringType }
            )
        ) { entry ->
            val dbId = entry.arguments?.getString("dbId").orEmpty()
            val recordArg = entry.arguments?.getString("recordId")
            val recordId = recordArg?.toLongOrNull()
            RecordEditScreen(
                dbId = dbId,
                recordId = recordId,
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack() }
            )
        }
    }
}
