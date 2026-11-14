# JmDNS uses reflection over its own service info classes (package is javax.jmdns).
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# BouncyCastle lightweight crypto primitives used by AirPlay.
-keep class org.bouncycastle.crypto.modes.ChaCha20Poly1305 { *; }
-keep class org.bouncycastle.crypto.macs.Poly1305 { *; }
-keep class org.bouncycastle.crypto.agreement.X25519Agreement { *; }
-keep class org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator { *; }
-keep class org.bouncycastle.crypto.generators.X25519KeyPairGenerator { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-dontwarn org.bouncycastle.**
