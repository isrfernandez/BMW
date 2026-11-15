# --- Cockpit F30: reglas de R8 para la versión Release ---
# Se encoge y optimiza el código, pero lo que se usa por reflexión se deja intacto.

# android.car y las clases de la radio se cargan por reflexión desde otros APK.
-keep class android.car.** { *; }
-dontwarn android.car.**

# Lectura del coche y de la radio: reflexión, Binder a mano y Proxy sobre interfaces del fabricante.
# Se conservan enteras (y con sus nombres) para que el Diagnóstico siga diciendo lo mismo.
-keep class com.cockpitf30.vehicle.probe.** { *; }
-keep class com.cockpitf30.vehicle.source.** { *; }
-keep class com.cockpitf30.radio.FactoryTuner { *; }
-keep class com.cockpitf30.radio.FactoryTuner$* { *; }

# Vosk usa JNA, que busca por nombre las clases, campos y métodos de Java desde código nativo.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Los ajustes guardan el nombre de los enum (valueOf): no se renombran.
-keepclassmembers enum com.cockpitf30.** { *; }

# Trazas de error legibles en el Diagnóstico.
-keepattributes SourceFile,LineNumberTable
