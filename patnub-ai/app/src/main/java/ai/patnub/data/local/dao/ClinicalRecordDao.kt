package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.ClinicalRecordEntity
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClinicalRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(records: List<ClinicalRecordEntity>)

    /**
     * Collection B for the Assistant (build plan Phase 2, step 2). Only issued records the
     * patient has consented to, and only for [patientId]. The caller passes the signed-in
     * patient, never an id taken from prompt text.
     */
    @Query(
        """
        SELECT * FROM clinical_records
        WHERE patient_id = :patientId
          AND approval_status = 'ISSUED'
          AND EXISTS (
            SELECT 1 FROM patient_consents c
            WHERE c.record_id = clinical_records.record_id
              AND c.patient_id = :patientId
              AND c.status = 'GRANTED'
          )
        ORDER BY created_at DESC
        """,
    )
    suspend fun getConsentedIssued(patientId: String): List<ClinicalRecordEntity>

    /** Records tab: everything issued to this patient, newest first. */
    @Query(
        "SELECT * FROM clinical_records WHERE patient_id = :patientId AND approval_status = 'ISSUED' ORDER BY created_at DESC",
    )
    fun observeIssued(patientId: String): Flow<List<ClinicalRecordEntity>>
}
