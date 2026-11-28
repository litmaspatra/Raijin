# Raijin ProGuard rules
# Add project specific ProGuard rules here.

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Room — keep entity and DAO names for schema export
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# Moshi — keep JSON model classes
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Compose — keep all @Composable functions from being removed
-keep class androidx.compose.** { *; }

# VO-2: JNI entry points for on-device Whisper
-keep class com.raijin.app.audio.WhisperNative { native <methods>; }

# On-device Kokoro voice: sherpa-onnx is called from native code, keep its classes.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.raijin.app.audio.SherpaKokoroSynth { *; }
