package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.MedicationReferenceEntity
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationReferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(meds: List<MedicationReferenceEntity>)

    @Query("DELETE FROM medication_references")
    suspend fun deleteAll()

    @Query("SELECT * FROM medication_references ORDER BY name")
    fun observeAll(): Flow<List<MedicationReferenceEntity>>
}
