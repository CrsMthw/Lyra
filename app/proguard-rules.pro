# AppAuth
-keep class net.openid.appauth.** { *; }

# Retrofit + Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.crsmthw.lyra.data.remote.model.** { *; }
-keep class com.crsmthw.lyra.data.local.CachedTrackList { *; }
-keep class com.crsmthw.lyra.data.local.LibraryCacheData { *; }

# Spotify App Remote
-keep class com.spotify.android.appremote.** { *; }
-keep interface com.spotify.android.appremote.** { *; }
-keep class com.spotify.protocol.** { *; }

# Spotify AAR references Jackson + its own annotations at compile time but doesn't ship them.
# R8 sees the references in the AAR bytecode — suppress the missing-class errors.
-dontwarn com.fasterxml.jackson.**
-dontwarn com.spotify.base.annotations.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# commons-math3 — used for Akima spline interpolation in the audio visualizer
-keep class org.apache.commons.math3.analysis.interpolation.AkimaSplineInterpolator { *; }
-keep class org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction  { *; }
-dontwarn org.apache.commons.math3.**
