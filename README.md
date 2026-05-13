# CurioDB

![CurioDB Logo](docs/images/icon.png)

A lightweight, fully offline Android app for tracking collections (watches, gear, books — anything) in user-defined databases.

- **Configurable schemas** per database: text, integer, number, date, boolean, image, enum
- **Aggregates** on numeric fields shown on the home screen (sum, avg, min, max, count)
- **Search, filter, sort** inside each database
- **Import/export** as JSON
- **Single-user, no network** — the manifest declares no internet or network permission, by design

## Project layout

```
curiodb/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/lcdcode/curiodb/
│       │   ├── CurioApp.kt
│       │   ├── MainActivity.kt
│       │   ├── data/          (FieldType, schema models, file manager)
│       │   └── ui/            (theme + screens)
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

Per-database on-disk layout (app-private storage):

```
filesDir/
├── databases/
│   └── <db-id>.curiodb        (SQLite)
└── images/
    └── <db-id>/
        └── <uuid>.jpg
```

## Building

This project does not include the Gradle wrapper JAR. Open in Android Studio (Iguana or later) — it will generate the wrapper on first sync. Then:

```
./gradlew :app:assembleDebug
```

## Tech used

- Kotlin 2.0 + Jetpack Compose (BOM `2024.09`)
- Material 3 with dynamic color
- AGP 8.5, minSdk 26, target/compileSdk 34
- Raw SQLite via `android.database.sqlite` (dynamic per-database schemas)
- kotlinx.serialization for JSON import/export
- Coil for image rendering
