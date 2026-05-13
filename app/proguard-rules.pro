# Keep Apollo generated types
-keep class net.unraidcontrol.app.graphql.** { *; }

# Hilt
-keepclasseswithmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }

# Tink (via androidx.security.crypto) references ErrorProne compile-only
# annotations that aren't on the runtime classpath. They carry no behavior,
# so silence the warnings.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
