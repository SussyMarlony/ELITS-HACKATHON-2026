package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.KnowledgeChunkEntity
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface KnowledgeChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(chunks: List<KnowledgeChunkEntity>)

    @Query("DELETE FROM general_knowledge_chunks")
    suspend fun deleteAll()

    /** Collection A. Retired passages are never returned (Spec §4.3). */
    @Query("SELECT * FROM general_knowledge_chunks WHERE status = 'APPROVED'")
    suspend fun getApproved(): List<KnowledgeChunkEntity>
}
