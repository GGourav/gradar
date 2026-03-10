# G Radar ProGuard Configuration
# Based on QRadar reference implementation

######################
# General Android
######################
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes EnclosingMethod

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

######################
# VpnService
######################
# Keep VpnService and its Builder
-keep class android.net.VpnService { *; }
-keep class android.net.VpnService$Builder { *; }

# Keep our VPN service implementation
-keep class com.gradar.service.** { *; }
-keepclassmembers class com.gradar.service.** { *; }

######################
# Photon Protocol
######################
# Keep all protocol parsing classes
-keep class com.gradar.protocol.** { *; }
-keepclassmembers class com.gradar.protocol.** { *; }

# Keep event code enums
-keepclassmembers enum com.gradar.protocol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

######################
# Entity Handlers
######################
-keep class com.gradar.handler.** { *; }
-keepclassmembers class com.gradar.handler.** { *; }

# Keep data models
-keep class com.gradar.model.** { *; }
-keepclassmembers class com.gradar.model.** { *; }

######################
# EventBus
######################
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keep class org.greenrobot.eventbus.** { *; }

######################
# OkHttp
######################
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

######################
# Gson
######################
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all model classes for Gson serialization
-keep class com.gradar.model.** { *; }

######################
# Room Database
######################
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

######################
# Coroutines
######################
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

######################
# Optimization
######################
# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Allow optimization
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# Aggressive optimization for release
-allowaccessmodification
-repackageclasses 'a'
