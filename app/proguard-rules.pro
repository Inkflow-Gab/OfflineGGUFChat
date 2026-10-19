# R8 / ProGuard rules for OfflineGGUFChat.
#
# Only used when the release variant is minified:
#   ./gradlew assembleRelease -Pofflinegguf.minify=true
# (isMinifyEnabled and isShrinkResources both read that flag, so the default
# release build does not run R8 at all.)
#
# Compose, Room, DataStore and lifecycle ship their own consumer rules; what
# follows covers only the parts of this app that R8 cannot infer.

# ---------------------------------------------------------------- diagnostics
# Keep line numbers so a release stack trace can still be read, and keep the
# annotations/metadata that reflection and serialization rely on.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,InnerClasses,Signature,Exceptions,EnclosingMethod
-renamesourcefileattribute SourceFile
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------- JNI
# llama_jni.cpp resolves its entry points by mangled name
# (Java_com_offlinegguf_chat_inference_LlamaNative_native...), so renaming any
# `external fun` would break the link at System.loadLibrary time.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The C++ side also calls *back* into the JVM. llama_jni.cpp does
# GetObjectClass(callback) and then GetMethodID(cls, "onTokenBytes", "([B)V") /
# GetMethodID(cls, "onError", "(Ljava/lang/String;)V"). The callback object is an
# anonymous `object : LlamaNative.GenerationCallback` inside NativeLlamaEngine,
# so both the interface and every implementation must keep those two names —
# if R8 renames them, GetMethodID fails and generation silently produces no
# tokens instead of crashing.
-keep class com.offlinegguf.chat.inference.LlamaNative { *; }
-keep interface com.offlinegguf.chat.inference.LlamaNative$GenerationCallback { *; }
-keepclassmembers class * implements com.offlinegguf.chat.inference.LlamaNative$GenerationCallback {
    public void onTokenBytes(byte[]);
    public void onError(java.lang.String);
}

# ---------------------------------------------------------------- serialization
# assets/catalog/models.json is decoded with an explicit serializer()
# companion call. Keep the generated serializers and the @Serializable models
# they describe; without this R8 strips the companion and decoding fails.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.offlinegguf.chat.**$$serializer { *; }
-keepclassmembers class com.offlinegguf.chat.** {
    *** Companion;
    *** INSTANCE;
}
-keepclasseswithmembers class com.offlinegguf.chat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------ misc
# Enum valueOf()/values() are used when parsing GGUF dtype and type ids.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable CREATOR fields, in case a screen state is ever passed between
# activities.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Never strip the Application subclass: it is named in AndroidManifest.xml.
-keep class com.offlinegguf.chat.App { *; }
