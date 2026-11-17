package ai.patnub.data.local.entity

// Room stores enums as their name (TEXT). Renaming a constant changes stored data,
// so a rename needs a database migration.

enum class UserRole { PATIENT, CLINICIAN }

/** Who performed an audited action. SYSTEM covers automatic checks such as QR verification. */
enum class ActorRole { PATIENT, CLINICIAN, SYSTEM }

enum class KnowledgeStatus { APPROVED, RETIRED }

enum class ApprovalStatus { DRAFT, ISSUED, SUPERSEDED }

enum class QrGrantStatus { ISSUED, REDEEMED, REVOKED }

enum class ConsentStatus { GRANTED, REVOKED }

enum class VisitItemType { PREP, RECAP }

/** Where a visit item came from. Only patients write visit items today (Spec §4.10). */
enum class VisitItemOrigin { PATIENT_REPORTED }
