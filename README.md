# Kiosco Web

App Android mínima para dejar una tablet mostrando **una URL a pantalla completa**, con la
**pantalla siempre encendida** y salida protegida con PIN. Inspirada en Fully Single App Kiosk,
pero enfocada en el caso de uso de un panel web / dashboard.

## Funciones

- WebView a pantalla completa (inmersivo, también sobre el notch).
- Pantalla siempre encendida (`FLAG_KEEP_SCREEN_ON`) y opción de WakeLock forzado.
- URL configurable desde la app o por `adb`/intent.
- PIN para entrar a los ajustes o salir (por defecto **1234**).
- 7 toques rápidos en la esquina superior izquierda → diálogo de PIN.
- Orientación configurable (sensor / vertical / horizontal).
- Reintento automático y aviso cuando no hay conexión.
- Descargas, selector de archivos y video fullscreen.
- Arranque automático al reiniciar (opcional).
- Modo kiosco estricto opcional con *Lock Task* (pinning o device owner).
- Sin analytics, sin permisos innecesarios, sin dependencias de red.

## Requisitos

- Android 7.0 (API 24) o superior.
- WebView del sistema actualizado.
- Para compilar: JDK 17, Android SDK 34 y Gradle 8.10+.

## Compilar

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Si `gradle` no está instalado, usá el wrapper de tu elección o el binario de Gradle 8.10.2.
El archivo `local.properties` debe apuntar al SDK (`sdk.dir=/ruta/a/Android/Sdk`).

## Instalar y configurar

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.kiosko.web/.MainActivity --es url "https://mi-panel.local"
```

Al abrir por primera vez pide la URL. Después:

1. Tocá **7 veces rápido** la esquina superior izquierda de la pantalla.
2. Ingresá el PIN (`1234` por defecto).
3. Cambiá URL, PIN, opciones de energía, orientación y modo kiosco.

Atajos útiles:

```bash
# Cambiar la URL sin abrir los ajustes
adb shell am start -n com.kiosko.web/.MainActivity --es url "https://otra-url"

# Ver logs
adb logcat -s KioscoWeb
```

## Modo kiosco (opcional)

### Nivel 1: inmersivo (sin configuración)

La app ya oculta barras y bloquea el botón atrás. Alcanza para la mayoría de los paneles.

### Nivel 2: screen pinning

Activá **Modo kiosco estricto** en los ajustes. Android muestra una confirmación y fija la app
en pantalla. Se sale con el gesto de pinning + PIN.

### Nivel 3: device owner (bloqueo real)

Requiere un equipo sin cuentas configuradas (o recién restablecido de fábrica):

```bash
adb shell dpm set-device-owner com.kiosko.web/.KioskDeviceAdminReceiver
```

Luego activá **Modo kiosco estricto**. La app se agrega sola a la lista de Lock Task.

Opcional, dejar la app como launcher principal:

```bash
adb shell cmd package set-home-activity com.kiosko.web/.MainActivity
```

Opcional, desactivar el apagado de pantalla a nivel sistema (además del flag de la app):

```bash
adb shell settings put system screen_off_timeout 2147483647
```

### Quitar el device owner

```bash
adb shell dpm remove-active-admin com.kiosko.web/.KioskDeviceAdminReceiver
```

## Estructura

```
app/src/main/java/com/kiosko/web/
├── MainActivity.kt        # WebView, fullscreen, energía, ajustes y PIN
└── BootReceiver.kt        # arranque automático
app/src/main/res/
├── layout/activity_main.xml
├── layout/dialog_settings.xml
├── values/{strings,arrays,themes,colors}.xml
├── xml/device_admin.xml
└── drawable/ic_launcher.xml
docs/analisis-fully.md     # análisis del APK de Fully con REA
```

## Limitaciones conocidas

- **Android 10+** restringe abrir actividades en segundo plano: el arranque automático puede
  no funcionar en algunos fabricantes. Solución estable: dejar la app como *Home* o usar
  device owner.
- El modo kiosco estricto sin device owner usa *screen pinning* y muestra un aviso del sistema.
- La zona de 7 toques (56×56 dp arriba a la izquierda) tapa una pequeña parte del sitio.
- No incluye acceso a ajustes del sistema, Knox, administración remota ni detección de
  movimiento; ver `docs/analisis-fully.md` para el mapa de funciones de Fully.
