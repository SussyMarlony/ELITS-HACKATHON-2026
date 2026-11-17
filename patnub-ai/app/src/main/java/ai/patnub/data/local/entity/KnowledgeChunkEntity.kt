package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.LocalDate

/**
 * Collection A: general medical reference passages, copied verbatim from public-domain sources
 * (MedlinePlus today; DOH Philippines once permission is in place).
 * Never holds patient data (Spec §6.1).
 */
@Entity(
    tableName = "general_knowledge_chunks",
    indices = [
        Index("status", "topic"),
        Index("source_id"),
    ],
)
data class KnowledgeChunkEntity(
    @PrimaryKey @ColumnInfo(name = "chunk_id") val chunkId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_title") val sourceTitle: String,
    @ColumnInfo(name = "publisher") val publisher: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "review_date") val reviewDate: LocalDate,
    @ColumnInfo(name = "status") val status: KnowledgeStatus,
    @ColumnInfo(name = "topic") val topic: String,
    @ColumnInfo(name = "content_text") val contentText: String,
    /** Embedding vector for retrieval. Null until the embedding pipeline exists (Phase 2). */
    @ColumnInfo(name = "embedding_blob") val embeddingBlob: ByteArray? = null,
    /** Where the text was copied from, word for word (content/build.py). */
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    /** Clinician who signed off this passage; null until clinical review (content/REVIEW.md). */
    @ColumnInfo(name = "reviewed_by") val reviewedBy: String? = null,
)
