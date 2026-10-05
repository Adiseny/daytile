# The only rules: nothing in the app is named by reflection, and it has no libraries
# beyond the Kotlin standard library, of which no class remains.
-allowaccessmodification

# That is checked: the build fails if one would ship. Only functions the compiler and R8
# write into the app's own classes are left of it (docs/kotlin-licence.txt), so an idiom
# that needs a class of its own (a callable reference, `lazy`, `Pair`, a range kept as an
# object, a function type in place of a platform interface) is caught here, not by size.
-checkdiscard class kotlin.** { <init>(...); }
-checkdiscard interface kotlin.**

# Move every renamed class into a single package so the dex carries no package names.
# Nothing finds a renamed class by its package: whatever is looked up by name (the
# manifest's components) is kept by its own rules and stays where it is.
-repackageclasses

# Kotlin's generated parameter and expression null checks only matter when Java passes
# null into Kotlin. Every path here is null-safe Kotlin or the framework, so they would
# only cost bytecode and a call per public function.
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
