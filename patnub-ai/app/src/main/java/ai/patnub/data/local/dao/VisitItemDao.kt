package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.VisitItemEntity
import ai.patnub.data.local.entity.VisitItemType
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitItemDao {
    @Upsert
    suspend fun upsert(item: VisitItemEntity)

    @Query("DELETE FROM visit_items WHERE item_id = :itemId AND patient_id = :patientId")
    suspend fun delete(itemId: String, patientId: String)

    @Query("SELECT * FROM visit_items WHERE patient_id = :patientId AND item_type = :type ORDER BY created_at DESC")
    fun observe(patientId: String, type: VisitItemType): Flow<List<VisitItemEntity>>
}
