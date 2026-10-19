# One Video G ProGuard rules.
# Release builds currently ship unminified (isMinifyEnabled = false).
# Media3 uses reflection in some paths; keep rules conservative if minification is enabled later.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
