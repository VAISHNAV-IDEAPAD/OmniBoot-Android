# Proguard rules for OmniBoot
-keepclassmembers class * {
    native <methods>;
}
-keep class com.omniboot.model.** { *; }
