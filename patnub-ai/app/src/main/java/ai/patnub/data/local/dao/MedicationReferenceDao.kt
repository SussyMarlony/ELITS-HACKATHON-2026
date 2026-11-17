package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.MedicationReferenceEntity
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationReferenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(meds: List<MedicationReferenceEntity>)

    @Query("SELECT * FROM medication_references ORDER BY name")
    fun observeAll(): Flow<List<MedicationReferenceEntity>>
}
