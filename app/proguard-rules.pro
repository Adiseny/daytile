# Release rules on top of each library's own. AGP's proguard-android-optimize.txt is not
# applied; the rules below are the parts of it this app needs. Left out are its rules for
# what the app does not have: view properties animated by name and android:onClick handlers
# (which kept every getter and setter of every view, 83 unused ones on Compose's root view
# alone), licensing services, JavaScript interfaces and the old support library.
-allowaccessmodification

-keepattributes AnnotationDefault,
                EnclosingMethod,
                InnerClasses,
                RuntimeVisibleAnnotations,
                RuntimeVisibleParameterAnnotations,
                RuntimeVisibleTypeAnnotations,
                Signature

# What the platform finds by name: native methods, enum values and parcelable creators.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Foldable-device libraries that exist only on the devices providing them. Compose's window
# size code can name them, and nothing in the app reaches it.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# Move every renamed class into a single package so the dex carries no package names.
# Nothing finds a renamed class by its package: whatever is looked up by name (the
# manifest's components) is kept by its own rules and stays where it is.
-repackageclasses

# Kotlin's generated parameter and expression null checks only matter when Java passes
# null into Kotlin. Every path here is null-safe Kotlin or the framework, so in the app
# and its libraries alike they would only cost bytecode and a call per public function.
# Explicit checks (!!, checkNotNull, requireNotNull) are other methods and remain.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
}

# libandroidx.graphics.path.so is left out of the APK (packaging in build.gradle.kts). It is
# loaded only when a path is iterated on Android 8-13, and PathIterator is the only way in,
# so it must stay unused: iterating a path would otherwise crash there.
-checkdiscard class androidx.graphics.path.PathIterator
