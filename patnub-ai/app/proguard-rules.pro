# Project-specific R8 keep rules. Minification is off for now (see app/build.gradle.kts).

# Room finds the generated PatnubDatabase_Impl by reflection.
-keep class * extends androidx.room3.RoomDatabase { <init>(); }

# JNI entry points for libmsr_engine will need keep rules here once the bridge lands.
