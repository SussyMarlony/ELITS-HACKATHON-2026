package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Patients and clinicians. Patient-only columns (age, barangay) and clinician-only
 * columns (specialty, facility, PRC license) are null for the other role.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "role") val role: UserRole,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "age") val age: Int? = null,
    @ColumnInfo(name = "barangay") val barangay: String? = null,
    @ColumnInfo(name = "specialty") val specialty: String? = null,
    @ColumnInfo(name = "facility") val facility: String? = null,
    @ColumnInfo(name = "prc_license_no") val prcLicenseNo: String? = null,
)
