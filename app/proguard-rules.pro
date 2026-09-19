# NakashTV — R8 rules. The file was empty while minifyEnabled=true; kotlinx.serialization + Retrofit suspend
# functions are the classic runtime breakers in a Release build that compiles fine.

# --- kotlinx.serialization (all DTOs are in tv.nakash.data.remote) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class tv.nakash.data.remote.**$$serializer { *; }
-keepclassmembers class tv.nakash.data.remote.** { *** Companion; }
-keepclasseswithmembers class tv.nakash.data.remote.** { kotlinx.serialization.KSerializer serializer(...); }
# Custom KSerializer objects referenced by @Serializable(with=...)
-keep class tv.nakash.data.remote.Lenient* { *; }

# --- Retrofit suspend functions (generic signatures must survive) ---
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface tv.nakash.data.remote.XtreamApi { *; }

# --- Media3 / Hilt / Room ship consumer rules; nothing extra needed. Keep enum names used in DB strings. ---
-keepclassmembers enum tv.nakash.domain.Normalizer$SourceKind { *; }

# Strip logging in release
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }
