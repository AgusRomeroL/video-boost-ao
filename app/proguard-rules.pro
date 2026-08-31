# Reglas R8 para la release minificada.
#
# Casi toda la app es codigo alcanzable de forma estatica y R8 lo resuelve solo.
# Lo que no puede ver son las piezas que entran por reflexion, por JNI o desde
# otro proceso, y son justo las del camino privilegiado (Shizuku y el cliente
# ADB embebido), que ademas falla en silencio: si R8 se lleva una de estas
# clases la app compila, instala y arranca igual, y solo se rompe el full-auto.
# De ahi que estas reglas sean deliberadamente conservadoras.
#
# Los componentes del manifest (MainActivity, VideoBoostService y el resto de
# servicios/receiver, mas rikka.shizuku.ShizukuProvider) los preserva AGP con
# las reglas que genera del propio manifest; no hace falta repetirlos aqui.

# --- Shizuku: UserService ---
# Shizuku carga el APK en un proceso con identidad shell (UID 2000) e instancia
# esta clase por nombre. Desde el proceso de la app nadie la construye ni llama
# a sus metodos, asi que sin este keep R8 la ve muerta entera.
-keep class com.agustin.videoboostao.UserService { *; }

# El AIDL que expone ese servicio. onTransact solo lo invoca el framework de
# binder, de modo que R8 tampoco ve quien llama a los metodos de la interfaz.
-keep interface com.agustin.videoboostao.IUserService { *; }
-keep class com.agustin.videoboostao.IUserService$* { *; }

# --- sun-security-android: certificado X.509 del cliente ADB ---
# OIDMap instancia las extensiones X.509 con Class.forName sobre nombres
# literales, y X509CertInfo/CertificateExtensions resuelven sus atributos por
# cadena ("version", "SubjectKeyIdentifier"...). Son 152 clases en total: afinar
# mas no compensa el riesgo.
-keep class android.sun.security.** { *; }
-keep class android.sun.misc.** { *; }
-dontwarn android.sun.**

# --- libadb-android y spake2 ---
# AbsAdbConnectionManager es la clase base que subclasamos y cuyos metodos
# abstractos resuelve la libreria; SslUtils y PairingConnectionCtx llegan a
# conscrypt por reflexion. Spake2Context es un wrapper JNI: sus metodos nativos
# se resuelven por nombre contra libspake2.so, asi que renombrarlos rompe el
# emparejamiento con un UnsatisfiedLinkError.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.** { *; }

# PRNGFixes intenta parchear el NativeCrypto de Harmony, que no existe en
# Android moderno; la llamada ya va en try/catch.
-dontwarn org.apache.harmony.**

# conscrypt y hiddenapibypass traen sus propias reglas de consumidor dentro del
# AAR (conscrypt hace -keep de todo su paquete por el JNI, hiddenapibypass
# conserva las plantillas de Helper). Solo falta silenciar el adaptador de
# KitKat de conscrypt, que referencia la SSLParametersImpl interna de la
# plataforma; con minSdk 33 esa ruta nunca se ejecuta.
-dontwarn com.android.org.conscrypt.**

# --- BouncyCastle ---
# De bcprov solo se usa HKDFBytesGenerator para derivar la clave del pairing
# SPAKE2, asi que R8 puede tirar el resto del proveedor: ahi esta la mayor
# parte del ahorro. bcprov compila contra APIs de JDK que no existen en Android
# y contra su propio codigo post-cuantico; nada de eso se alcanza.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
