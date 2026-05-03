# Forge OS ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * { public protected *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class com.forge.os.data.remote.dto.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class com.forge.os.domain.model.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent

# Chaquopy (Python runtime)
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Kotlinx-Serialization
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.forge.os.**$$serializer { *; }
-keepclassmembers class com.forge.os.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.forge.os.** { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.flow.**

# Forge data + domain models touched by reflection
-keep class com.forge.os.domain.config.** { *; }
-keep class com.forge.os.domain.memory.** { *; }
-keep class com.forge.os.domain.cron.** { *; }
-keep class com.forge.os.domain.plugins.** { *; }
-keep class com.forge.os.domain.agents.** { *; }

# WorkManager Hilt workers
-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Strip verbose debug logs in release
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
