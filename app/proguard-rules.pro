-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Serializable classes and members (e.g., SerializableReceiver passed via Intent extras)
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# AirPlay networking, discovery (mDNS/BLE), RTSP, plist, FairPlay/SAP, and session streams
-keep class com.dhatech.roku.airplay.** { *; }

# Screen capture (MediaProjection, VirtualDisplay, MediaCodec) and audio capture/ALAC encoding
-keep class com.dhatech.roku.capture.** { *; }

# Foreground mirroring service, Quick Settings tile, Intent models, and audio routing
-keep class com.dhatech.roku.service.** { *; }
-keep class com.dhatech.roku.audio.** { *; }

# JmDNS mDNS discovery (actual package is javax.jmdns, not org.jmdns)
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**

# BouncyCastle lightweight crypto primitives used by AirPlay (ChaCha20-Poly1305, Poly1305, X25519, Ed25519)
-keep class org.bouncycastle.crypto.modes.ChaCha20Poly1305 { *; }
-keep class org.bouncycastle.crypto.macs.Poly1305 { *; }
-keep class org.bouncycastle.crypto.agreement.X25519Agreement { *; }
-keep class org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator { *; }
-keep class org.bouncycastle.crypto.generators.X25519KeyPairGenerator { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-dontwarn org.bouncycastle.**
