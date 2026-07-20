# Video Boost AO (Always-On) — Pixel 10 Pro

App sideload (sin root) que reactiva **Video Boost** automáticamente cada vez que
abrís Pixel Camera en modo video. Un `AccessibilityService` detecta la cámara en
primer plano, abre el panel **Video Settings**, lee el estado del toggle
**Video Boost** y lo enciende solo si está apagado (nunca clickea a ciegas —
si ya estaba activo no lo toca). Después cierra el panel para dejar el visor
como estaba.

**Por qué existe:** Pixel Camera apaga Video Boost deliberadamente en cada
sesión (Google lo hizo persistente unas semanas en feb-2025 y lo revirtió). No
hay flag, intent ni preferencia que lo fije sin root; la única vía sin root es
re-activarlo por accesibilidad en cada apertura.

## Instalación

1. **Instalar el APK** (`app/build/outputs/apk/debug/app-debug.apk`):
   - Por cable: `adb install app-debug.apk` (autorizar depuración USB en el teléfono), o
   - Copiando el APK al teléfono y abriéndolo (permitir "instalar apps desconocidas"), o
   - Con [SAI (Split APKs Installer)](https://github.com/Aefyr/SAI) — **recomendado**:
     al instalar por sesión, Android no lo trata como sideload y **no aplica la
     restricción de "ajustes restringidos"** del paso 3.

2. **Habilitar el servicio:** abrir la app *Video Boost AO* → "Abrir ajustes de
   Accesibilidad" → habilitar *Video Boost Siempre Activo*.

3. **Si aparece "Ajuste restringido"** (normal en APK instalado a mano en
   Android 13+): en la app tocá "Abrir Información de la app" → menú **⋮**
   (arriba a la derecha) → **"Permitir ajustes restringidos"** (PIN/huella) →
   repetir el paso 2. Si el menú ⋮ no aparece: abrí la app, cerrala desde
   multitarea, y entrá por pulsación larga del icono → "Información de la app".

4. **Probar:** abrir Pixel Camera → modo Video. En ~0,5 s el servicio abre el
   panel, activa Video Boost (icono con chispa arriba a la izquierda del visor)
   y cierra el panel. La app tiene un botón "Probar ahora" que lo hace por vos.

La app (Compose, Material 3 Expressive) te guía el alta del servicio con un
botón que abre Accesibilidad **resaltando** la entrada del servicio, e incluye
un **interruptor maestro** para pausar/reanudar la función sin tener que
deshabilitar la accesibilidad.

## Verificación completa

- Cerrar la cámara desde multitarea y reabrir → se reactiva solo.
- Cambiar foto↔video con Boost ya activo → no lo toca (idempotente).
- Modo foto → no hace nada.
- Grabar un video corto → confirmar en Google Photos el procesamiento Boost.
- Reiniciar el teléfono → el servicio persiste.
- Logs: `adb logcat -s VideoBoostAO`

## Idiomas

Funciona en cualquier idioma del sistema soportado por Pixel Camera (74
idiomas — todos los mercados oficiales del Pixel y más). Las etiquetas
localizadas del chip "Video" y de la fila "Video Boost" NO están adivinadas:
se extraen directamente de la propia APK de Pixel Camera del dispositivo y
viven en [`CameraLabels.kt`](app/src/main/java/com/agustin/videoboostao/CameraLabels.kt).

Detalles de la lógica:

- El servicio lee el idioma del sistema y busca las etiquetas de ese idioma,
  con inglés como respaldo.
- La fila Video Boost se identifica por texto localizado; el botón "on", de
  forma primaria por su `contentDescription` y, como respaldo, por posición
  **consciente de RTL**: en árabe/hebreo/farsi/urdu el orden de los botones se
  invierte (el "on" queda a la izquierda), y el servicio lo contempla. Esto
  importa porque en algunos idiomas los botones exponen `contentDescription`
  vacío y solo queda la vía posicional.

## Si deja de funcionar (mantenimiento)

El `resource-id` de apertura del panel vive en
[`Selectors.kt`](app/src/main/java/com/agustin/videoboostao/Selectors.kt); las
etiquetas localizadas en `CameraLabels.kt`. Cada Feature Drop de Pixel Camera
puede cambiar textos, ids o jerarquía.

Para re-anclar los `resource-id` (dump en vivo):

```
# Con la cámara en modo video y el panel Video Settings abierto:
adb exec-out uiautomator dump /dev/tty
```

Para regenerar las etiquetas localizadas desde la APK real (recomendado tras
un update de la cámara), el pipeline usado fue:

```
adb shell pm path com.google.android.GoogleCamera      # ubicar base.apk
adb pull <base.apk>
aapt2 dump resources base.apk > gcam-resources.txt     # build-tools/aapt2
# extraer string/sapphire_label (fila), string/mode_video (chip),
# string/sapphire_on_desc (contentDescription del botón "on")
```

El script [`tools/gen-labels.ps1`](tools/gen-labels.ps1) automatiza la
extracción y regenera `CameraLabels.kt` (74 idiomas, variantes regionales
fusionadas por idioma).

## Build

Requiere Android SDK (platform 36) y JDK 17+ (sirve el JBR de Android Studio):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
gradle assembleDebug     # debug, para desarrollo
gradle assembleRelease   # release firmado (necesita keystore.properties)
```

**Firma release:** las credenciales van en `keystore.properties` (raíz, fuera
de git) apuntando a un keystore **fuera del repo**. Formato:

```properties
storeFile=RUTA\\AL\\videoboostao-release.keystore
storePassword=...
keyAlias=videoboostao
keyPassword=...
```

⚠️ **Respaldá el keystore.** Si lo perdés no podés volver a firmar
actualizaciones con la misma identidad (los usuarios tendrían que desinstalar
y reinstalar). Guardá una copia segura.

## Distribución

**Google Play: NO recomendado.** La app usa un `AccessibilityService` para un
fin que no es de accesibilidad (automatiza la UI de otra app). La política de
Google Play exige declararlo y aprobarlo, y **prohíbe** usar la API de
accesibilidad para "cambiar/aprovechar de forma engañosa la interfaz" de otra
app o cambiar ajustes — justo lo que hace esto. El riesgo no es solo rechazo:
puede terminar en **suspensión de la cuenta de desarrollador**. No vale la pena.

**Vía recomendada (sideload, open source):**

1. **GitHub** — repo público con la licencia MIT; subir el **APK release
   firmado** como asset en cada *Release* con un tag `vX.Y`. Es la fuente de
   verdad para todo lo demás.
2. **Obtainium** — los usuarios agregan la URL del repo y reciben
   actualizaciones automáticas directo desde tus GitHub Releases. Cero
   infraestructura de tu lado. Es la mejor recomendación para tus usuarios.
3. **IzzyOnDroid** — repo compatible con F-Droid que **distribuye tu propio APK
   firmado** (no recompila desde fuente como F-Droid oficial). Chequea tu repo
   a diario y publica en ~24 h. Da un canal de actualización a clientes tipo
   F-Droid y más alcance/confianza. Requiere enviar la app a su lista.
4. **APKMirror** — opcional, para alcance masivo por buscador; sin canal de
   actualización propio.

**Fricción a documentar para los usuarios:** en Android 13+ una app sideload
necesita "Permitir ajustes restringidos" para habilitar accesibilidad (ya está
explicado arriba y guiado dentro de la app). Instalar vía Obtainium/un
instalador por sesión reduce esa fricción.

## Donaciones

Botón configurable en la app (`DonationConfig.DONATE_URL`, hoy vacío → botón
oculto). Recomendación para un creador en **México**:

- **Ko-fi (recomendado):** **0 % de comisión** en donaciones/propinas, sin cuota
  mensual, payout **directo e instantáneo** a tu PayPal o Stripe (solo el fee de
  procesamiento ~2,9 % + fijo). Un solo link (`ko-fi.com/tuusuario`) que se pega
  en `DONATE_URL`. Funciona para creadores en México (PayPal MX disponible).
- **GitHub Sponsors (secundario):** 0 % de GitHub, encaja natural con el repo
  open source, pero el payout usa Stripe Connect y la elegibilidad/pago a México
  no está garantizada; requiere solicitud y aprobación. Útil como segundo botón
  en el repo si te aceptan.
- Evitar **Buy Me a Coffee** (5 % de comisión) frente al 0 % de Ko-fi.

Para conectarlo: crear la cuenta de Ko-fi, poner la URL en
[`DonationConfig.kt`](app/src/main/java/com/agustin/videoboostao/DonationConfig.kt)
y recompilar. El botón "Apoyar el proyecto" aparece solo cuando la URL no está
vacía. Nota: es un link externo (no el billing de Play), así que no aplica
comisión de Google.

## Advertencias

- **Consumo:** Video Boost procesa en la nube vía Google Photos — crea dos
  copias por video y consume datos/almacenamiento. Siempre activo = más consumo.
- **Privacidad:** el servicio filtra por código los eventos a
  `com.google.android.GoogleCamera` y solo lee/toca controles dentro de Pixel
  Camera; no actúa sobre ninguna otra app.
- **Fragilidad:** depende de la UI de Pixel Camera; no es "fire and forget".
