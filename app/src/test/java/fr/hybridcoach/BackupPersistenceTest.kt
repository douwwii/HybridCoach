package fr.hybridcoach

import android.content.ContentValues
import android.content.Context
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.backup.*
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupPersistenceTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var db: CoachDatabase
    private lateinit var preferences: AthletePreferences
    private lateinit var service: BackupService

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CoachDatabase::class.java).build()
        preferences = AthletePreferences(context, db)
        service = BackupService(context, db, preferences)
        val info =
            requireNotNull(
                context.packageManager.resolveContentProvider("${context.packageName}.files", 0)
            )
        FileProvider().attachInfo(context, info)
    }

    @After
    fun cleanup() {
        db.close()
    }

    private fun fileUri(name: String): android.net.Uri {
        val file =
            File(context.cacheDir, "exports/$name").apply {
                parentFile!!.mkdirs()
                createNewFile()
            }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private suspend fun existingTarget() {
        db.dao()
            .putSettings(
                AthleteSettingsEntity(
                    profileJson = codec.encodeToString(AthleteProfile(vma = "14")),
                    theme = "dark",
                )
            )
        db.dao()
            .putWorkout(
                PlannedWorkout(
                    "previous",
                    "Ancien carnet",
                    "2026-10-01",
                    Sport.RUN,
                    planJson = codec.encodeToString(WorkoutPlan()),
                    notes = "À conserver pour retour arrière",
                )
            )
    }

    @Test
    fun exportedJsonRestoresEveryTableOnAnotherDatabaseAndRemainsIdempotent() = runBlocking {
        BackupFixture.populate(db)
        context
            .getSharedPreferences("garmin-personal", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted", "synthetic-private-key-never-export")
            .commit()
        val uri = fileUri("migration.json")
        val exported = service.export(uri)
        val text =
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertFalse(text.contains("synthetic-private-key-never-export"))
        assertEquals(exported, service.preview(uri))
        val target = Room.inMemoryDatabaseBuilder(context, CoachDatabase::class.java).build()
        try {
            val targetPreferences = AthletePreferences(context, target)
            val targetService = BackupService(context, target, targetPreferences)
            targetService.restore(BackupCodec.decode(text))
            assertEquals(exported.data, targetService.snapshot().data)
            targetService.restore(BackupCodec.decode(text))
            assertEquals(exported.data, targetService.snapshot().data)
            assertEquals(exported.data.profile, targetPreferences.profile.first())
            assertEquals("light", targetPreferences.theme.first())
            assertEquals(
                "synthetic-private-key-never-export",
                context
                    .getSharedPreferences("garmin-personal", Context.MODE_PRIVATE)
                    .getString("encrypted", null),
            )
        } finally {
            target.close()
        }
    }

    @Test
    fun previousCarnetIsRecoverableAfterReplacementIncludingProfile() = runBlocking {
        existingTarget()
        val before = service.snapshot()
        service.restore(BackupFixture.document())
        assertNull(db.dao().workout("previous"))
        assertTrue(service.hasRecovery())
        val uri = fileUri("previous.json")
        service.exportRecovery(uri)
        val previous = service.preview(uri)
        assertEquals(before.data, previous.data)
        service.restore(previous)
        assertEquals(before.data, service.snapshot().data)
    }

    @Test
    fun sqlFailureRollsBackAllTablesAndProfileAfterClearingStarts() = runBlocking {
        existingTarget()
        val before = service.snapshot().data
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_import BEFORE INSERT ON workouts WHEN NEW.id='run' BEGIN SELECT RAISE(ABORT, 'synthetic import failure'); END"
        )
        assertNotNull(runCatching { service.restore(BackupFixture.document()) }.exceptionOrNull())
        assertEquals(before, service.snapshot().data)
        assertTrue(service.hasRecovery())
    }

    @Test
    fun invalidDocumentDoesNotModifyTargetOrCreateRecoveryFile() = runBlocking {
        existingTarget()
        val before = service.snapshot().data
        val document = BackupFixture.document()
        val broken =
            document.copy(
                data =
                    document.data.copy(
                        workouts = document.data.workouts.map { it.copy(preparationId = "absent") }
                    )
            )
        assertNotNull(runCatching { service.restore(broken) }.exceptionOrNull())
        assertEquals(before, service.snapshot().data)
        assertFalse(service.hasRecovery())
    }

    @Test
    fun restoredDataAndProfileSurviveClosingAndReopeningDatabase() = runBlocking {
        val filename = "backup-reopen.db"
        val persisted = Room.databaseBuilder(context, CoachDatabase::class.java, filename).build()
        val p = AthletePreferences(context, persisted)
        val backup = BackupService(context, persisted, p)
        backup.restore(BackupFixture.document())
        val expected = backup.snapshot().data
        persisted.close()
        val reopened = Room.databaseBuilder(context, CoachDatabase::class.java, filename).build()
        try {
            assertEquals(
                expected,
                BackupService(context, reopened, AthletePreferences(context, reopened))
                    .snapshot()
                    .data,
            )
        } finally {
            reopened.close()
            context.deleteDatabase(filename)
        }
    }

    @Test
    fun unreadableOrWrongFilesAreRejectedWithoutChangingTheCarnet() = runBlocking {
        existingTarget()
        val before = service.snapshot().data
        val uri = fileUri("wrong.json")
        context.contentResolver.openOutputStream(uri)!!.use {
            it.write("{private unfinished file".toByteArray())
        }
        val error = runCatching { service.preview(uri) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message!!.contains("private unfinished"))
        assertEquals(before, service.snapshot().data)
    }

    @Test
    fun inabilityToWriteTheSafetyCopyAbortsBeforeReplacingAnything() = runBlocking {
        existingTarget()
        val before = service.snapshot().data
        File(context.filesDir, "backup").writeText("synthetic obstruction")
        val error = runCatching { service.restore(BackupFixture.document()) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("carnet précédent"))
        assertEquals(before, service.snapshot().data)
    }

    @Test
    fun anEmptyBackupCanReplaceTheCarnetWithoutLeavingOrphans() = runBlocking {
        BackupFixture.populate(db)
        val old = service.snapshot().data
        val empty =
            old.copy(
                profile = AthleteProfile(),
                theme = "dark",
                exercises = emptyList(),
                templates = emptyList(),
                goals = emptyList(),
                preparations = emptyList(),
                workouts = emptyList(),
                results = emptyList(),
                subGoals = emptyList(),
                workoutSubGoals = emptyList(),
                garminActivities = emptyList(),
                garminSync = emptyList(),
            )
        service.restore(BackupFixture.document().copy(data = empty))
        assertEquals(empty, service.snapshot().data)
        val uri = fileUri("before-empty.json")
        service.exportRecovery(uri)
        assertEquals(old, service.preview(uri).data)
    }

    @Test
    fun garminSyncAfterTransferDoesNotDuplicateOrOverwriteLocalCorrections() = runBlocking {
        val document = BackupFixture.document()
        service.restore(document)
        val remote =
            codec.decodeFromString<GarminActivity>(document.data.garminActivities.single().payload)
        val changes =
            GarminImporter(db).importPersonal(GarminSnapshot("personal:42", 8, listOf(remote)))
        assertEquals(0, changes)
        assertEquals(2, db.dao().allWorkouts().size)
        assertEquals("9.8", parseResult(db.dao().result("run")!!.payload).distanceKm)
        assertEquals("Correction personnelle", parseResult(db.dao().result("run")!!.payload).notes)
    }

    @Test
    fun versionThreeMigrationAndLegacyDataStorePreserveTheEntireExistingCarnet() = runBlocking {
        val filename = "backup-v3.db"
        val schema =
            JSONObject(
                    javaClass.getResourceAsStream("/database-v3.json")!!.bufferedReader().use {
                        it.readText()
                    }
                )
                .getJSONObject("database")
        val fixture = BackupFixture.document().data
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(
                    entity
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(
                    indices
                        .getJSONObject(j)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
            }
            old.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            old.execSQL(
                "INSERT INTO room_master_table VALUES (42,?)",
                arrayOf(schema.getString("identityHash")),
            )
            fun insert(table: String, raw: JsonObject) {
                val values = ContentValues()
                raw.forEach { (key, element) ->
                    val value = element.jsonPrimitive
                    when {
                        value == JsonNull -> values.putNull(key)
                        value.isString -> values.put(key, value.content)
                        value.booleanOrNull != null -> values.put(key, if (value.boolean) 1 else 0)
                        value.longOrNull != null -> values.put(key, value.long)
                        else -> values.put(key, value.double)
                    }
                }
                old.insertOrThrow(table, null, values)
            }
            fixture.exercises.forEach {
                insert("exercises", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.templates.forEach {
                insert("templates", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.goals.forEach { insert("goals", codec.encodeToJsonElement(it).jsonObject) }
            fixture.preparations.forEach {
                insert("preparations", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.subGoals.forEach {
                insert("subGoals", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.workouts.forEach {
                insert("workouts", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.results.forEach { insert("results", codec.encodeToJsonElement(it).jsonObject) }
            fixture.workoutSubGoals.forEach {
                insert("workoutSubGoals", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.garminActivities.forEach {
                insert("garminActivities", codec.encodeToJsonElement(it).jsonObject)
            }
            fixture.garminSync.forEach {
                insert("garminSync", codec.encodeToJsonElement(it).jsonObject)
            }
            old.version = 3
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val legacy =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.filesDir, "legacy-athlete.preferences_pb")
            }
        try {
            legacy.edit {
                it[stringPreferencesKey("profile")] = codec.encodeToString(fixture.profile)
                it[stringPreferencesKey("theme")] = fixture.theme
            }
            val migrated =
                Room.databaseBuilder(context, CoachDatabase::class.java, filename)
                    .addMigrations(MIGRATION_3_4)
                    .build()
            try {
                val migratedPreferences = AthletePreferences(context, migrated, legacy)
                val migratedData =
                    BackupService(context, migrated, migratedPreferences).snapshot().data
                assertEquals(fixture.profile, migratedData.profile)
                assertEquals(fixture.theme, migratedData.theme)
                assertEquals(fixture.workouts.toSet(), migratedData.workouts.toSet())
                assertEquals(fixture.garminActivities, migratedData.garminActivities)
                assertEquals(fixture.results.toSet(), migratedData.results.toSet())
                assertEquals(fixture.workoutSubGoals.toSet(), migratedData.workoutSubGoals.toSet())
                assertEquals(DATABASE_VERSION, migrated.openHelper.writableDatabase.version)
                migratedPreferences.save(AthleteProfile(vma = "19"), "system")
                migratedPreferences.ensureInitialized()
                assertEquals("19", migratedPreferences.profile.first().vma)
                assertEquals(
                    codec.encodeToString(fixture.profile),
                    legacy.data.first()[stringPreferencesKey("profile")],
                )
            } finally {
                migrated.close()
            }
        } finally {
            scope.cancel()
            context.deleteDatabase(filename)
        }
    }
}
