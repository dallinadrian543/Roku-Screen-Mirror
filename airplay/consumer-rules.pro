# JmDNS uses reflection over its own service info classes (package is javax.jmdns).
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# BouncyCastle registers providers and crypto primitives used dynamically.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
