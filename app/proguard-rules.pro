# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Chaquopy SDK.

# Keep Chaquopy classes
-keep class com.chaquo.python.** { *; }

# Keep Python modules
-keep class butler.** { *; }
-keep class local_interpreter.** { *; }
-keep class plugin.** { *; }

# Keep model classes
-keep class com.butler.app.bridge.model.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Jetpack Compose
-keep class androidx.compose.** { *; }

# Keep R8 from removing classes that are looked up dynamically
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
