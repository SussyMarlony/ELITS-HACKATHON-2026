package ai.patnub.data.local

import ai.patnub.data.local.entity.PrescribedMedication
import androidx.room3.ColumnTypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

private val json = Json { ignoreUnknownKeys = true }
private val stringListSerializer = ListSerializer(String.serializer())
private val medicationListSerializer = ListSerializer(PrescribedMedication.serializer())

/**
 * Column conversions. Room applies these null-safely, so nullable columns
 * (for example `redeemed_at`) work with the non-null converters below.
 */
class Converters {
    /** Instants are stored as epoch milliseconds (INTEGER). */
    @ColumnTypeConverter
    fun instantToEpochMillis(value: Instant): Long = value.toEpochMilli()

    @ColumnTypeConverter
    fun epochMillisToInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    /** Calendar dates are stored as ISO-8601 text (`2026-10-02`). */
    @ColumnTypeConverter
    fun localDateToIso(value: LocalDate): String = value.toString()

    @ColumnTypeConverter
    fun isoToLocalDate(value: String): LocalDate = LocalDate.parse(value)

    /** `*_json` list columns are stored as JSON arrays (TEXT). */
    @ColumnTypeConverter
    fun stringListToJson(value: List<String>): String = json.encodeToString(stringListSerializer, value)

    @ColumnTypeConverter
    fun jsonToStringList(value: String): List<String> = json.decodeFromString(stringListSerializer, value)

    @ColumnTypeConverter
    fun medicationsToJson(value: List<PrescribedMedication>): String =
        json.encodeToString(medicationListSerializer, value)

    @ColumnTypeConverter
    fun jsonToMedications(value: String): List<PrescribedMedication> =
        json.decodeFromString(medicationListSerializer, value)
}
