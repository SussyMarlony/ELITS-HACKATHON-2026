package ai.patnub.data.local

import ai.patnub.data.local.entity.UserEntity
import ai.patnub.data.local.entity.UserRole

/** Demo accounts from the build plan (Phase 1, `users`). Fictional people. */
object SeedData {
    val users = listOf(
        UserEntity(
            userId = "p1",
            role = UserRole.PATIENT,
            displayName = "Maria Dela Cruz",
            age = 54,
            barangay = "Brgy. Malinis",
        ),
        UserEntity(
            userId = "p2",
            role = UserRole.PATIENT,
            displayName = "Jose Ramos",
            age = 67,
            barangay = "Brgy. Bagong Silang",
        ),
        UserEntity(
            userId = "p3",
            role = UserRole.PATIENT,
            displayName = "Liza Santos",
            age = 29,
            barangay = "Brgy. Mabini",
        ),
        UserEntity(
            userId = "d1",
            role = UserRole.CLINICIAN,
            displayName = "Dr. Ana Reyes",
            specialty = "Internal Medicine",
            facility = "Rural Health Unit · San Isidro",
            prcLicenseNo = "0123456",
        ),
    )
}

/** Safe to run on every launch: existing rows are never overwritten. */
suspend fun PatnubDatabase.seedDemoData() {
    userDao().insertOrIgnore(SeedData.users)
}
