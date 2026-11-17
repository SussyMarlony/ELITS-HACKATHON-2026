package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.AuditEventEntity
import androidx.room3.Dao
import androidx.room3.Insert

@Dao
interface AuditEventDao {
    @Insert
    suspend fun insert(event: AuditEventEntity)
}
