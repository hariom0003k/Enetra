# Add project specific ProGuard rules here.

# Keep ML Kit face detection models
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep CameraX
-keep class androidx.camera.** { *; }

# Keep our own classes
-keep class com.example.peepguard.** { *; }

# Keep accessibility service metadata
-keep public class * extends android.accessibilityservice.AccessibilityService

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ViewBinding
-keep class com.example.peepguard.databinding.** { *; }
