# Bluright Proguard rules
# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * {
    @com.google.mediapipe.** *;
}
