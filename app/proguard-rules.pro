# Keep Apollo generated types
-keep class net.unraidcontrol.app.graphql.** { *; }

# Hilt
-keepclasseswithmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }
