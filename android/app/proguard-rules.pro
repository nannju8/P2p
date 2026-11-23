-keepattributes Signature,InnerClasses,EnclosingMethod

# Garde uniquement ce qui est nécessaire à J-PAKE (BouncyCastle) et au serveur web CIO (Ktor)
# pour laisser R8 supprimer les Mo inutilisés sans casser l'app.
-keep class org.bouncycastle.crypto.agreement.jpake.** { *; }
-keep class org.bouncycastle.crypto.generators.HKDFBytesGenerator { *; }
-keep class org.bouncycastle.crypto.digests.SHA256Digest { *; }
-dontwarn org.bouncycastle.**

-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.engine.** { *; }
-dontwarn io.ktor.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
