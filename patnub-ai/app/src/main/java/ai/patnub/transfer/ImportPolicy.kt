package ai.patnub.transfer

/** One row per record ID; only a newer version may replace a saved one (spec §4.6.1). */
object ImportPolicy {
    enum class Decision { SAVE_NEW, REPLACE_OLDER, ALREADY_SAVED }

    fun decide(savedVersion: Int?, incomingVersion: Int): Decision = when {
        savedVersion == null -> Decision.SAVE_NEW
        incomingVersion > savedVersion -> Decision.REPLACE_OLDER
        else -> Decision.ALREADY_SAVED // replay of the same code, or an older version
    }
}
