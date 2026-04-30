# Proguard rules for EDS Overlay
-keepattributes *Annotation*

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.eds.overlay.data.EdsPoint { *; }
-keep class com.eds.overlay.data.EdsRepository$RawEdsEntry { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Strip debug and verbose logs in release builds (privacy)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
}
