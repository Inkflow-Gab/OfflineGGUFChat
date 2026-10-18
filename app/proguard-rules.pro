# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/share/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep llama.cpp JNI native methods
-keep class com.offlinegguf.chat.inference.LlamaNative { *; }

# Keep Room entities
-keep class com.offlinegguf.chat.data.local.entity.** { *; }

-keepclassmembers class * {
    @androidx.room.* *;
}
