-dontwarn javax.lang.model.element.Modifier
-keep class com.privateplanner.benchmark.** { *; }
-keep class kotlin.** { *; }
-keep @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * {
    @org.junit.Test <methods>;
}
