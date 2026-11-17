package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.PatientConsentEntity
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy

@Dao
interface PatientConsentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(consent: PatientConsentEntity)
}
