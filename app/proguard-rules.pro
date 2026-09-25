# Release rules on top of proguard-android-optimize.txt and each library's own rules.

# Move every renamed class into a single package so the dex carries no package names.
# Nothing finds a renamed class by its package: whatever is looked up by name (Room's
# generated database, manifest components, startup initialisers) is kept by its own
# rules and stays where it is.
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
