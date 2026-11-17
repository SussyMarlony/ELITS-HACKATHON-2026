package ai.patnub.data.local

import ai.patnub.data.local.dao.UserDao
import ai.patnub.data.local.entity.AuditEventEntity
import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.data.local.entity.KnowledgeChunkEntity
import ai.patnub.data.local.entity.MedicationReferenceEntity
import ai.patnub.data.local.entity.PatientConsentEntity
import ai.patnub.data.local.entity.QrGrantEntity
import ai.patnub.data.local.entity.UserEntity
import ai.patnub.data.local.entity.VisitItemEntity
import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * The on-device database (build plan Phase 1, Spec §6). Tables and column names
 * follow the build plan so its SQL examples apply unchanged.
 */
@Database(
    entities = [
        UserEntity::class,
        KnowledgeChunkEntity::class,
        ClinicalRecordEntity::class,
        QrGrantEntity::class,
        PatientConsentEntity::class,
        MedicationReferenceEntity::class,
        VisitItemEntity::class,
        AuditEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class PatnubDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        const val FILE_NAME = "patnub_local.db"

        /**
         * Uses the bundled SQLite build instead of the phone's system SQLite, so every
         * device runs the same SQLite version with FTS5 available for retrieval later.
         */
        fun create(context: Context): PatnubDatabase {
            val appContext = context.applicationContext
            return Room.databaseBuilder<PatnubDatabase>(
                context = appContext,
                name = appContext.getDatabasePath(FILE_NAME).absolutePath,
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
    }
}
