# ML Kit finds its components through reflection and reads protobuf-lite fields that R8's full mode
# would strip or rename; without these rules text recognition fails with a NullPointerException in
# the minified build.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.internal.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-dontwarn com.google.mlkit.**
