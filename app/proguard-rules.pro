# Riduce solo il codice inutilizzato (soprattutto le icone Compose); le librerie native restano intatte.
-dontobfuscate

# AI on-device: classi chiamate dal codice nativo (JNI)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# PdfBox-Android (risorse e caricamento dinamico dei font)
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# ML Kit / Play services
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**

# Parser XML (SAX) usati dai lettori Office
-keep class org.xml.sax.** { *; }
-keep class javax.xml.** { *; }

# Classi dell'app (dati e motore)
-keep class it.tal.taldoc.core.** { *; }
