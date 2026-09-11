# Malachi's R8 configuration: shrink, never obfuscate.
#
# The debug log is this app's only diagnostic channel from a phone, and a stack trace whose frames
# have been renamed is worth nothing to the person reading it. So names stay exactly as written,
# and what R8 does here is remove code nothing reaches — the unused half of Compose, of OkHttp and
# of the Kotlin standard library, which together with the extended icon set were most of a 45 MB
# release APK. Nothing in this app reaches a class by name at runtime (no Class.forName, no
# getIdentifier, every serializer passed explicitly), so no keep rule of ours is load-bearing
# today; the ones below are kotlinx.serialization's own, for the day a call site stops passing
# its serializer and the library looks for it through the Companion.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and generated ones) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
