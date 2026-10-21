# Análisis de Fully Single App Kiosk (com.fullykiosk.singleapp)

Análisis estático con REA (jadx + apktool) sobre el APK **1.21.4-play** (versionCode 1510,
actualizado el 11/10/2026), descargado como XAPK y analizando el APK base más sus splits
(`arm64-v8a`, `en`, `mdpi`).

## Ficha técnica

| Dato | Valor |
|---|---|
| Paquete | `com.fullykiosk.singleapp` |
| Versión | 1.21.4-play (1510) |
| minSdk / targetSdk | 32 (Android 12) / 36 |
| Clases DEX | ~7.491 |
| Ofuscación | Sí (R8/ProGuard); núcleo en `de.ozerov.fully.*` y `defpackage.*` |
| SDKs de terceros | Knox (Samsung), ACRA, AltBeacon, AndroidX, ExoPlayer, Billing |
| Permisos | ~60, incluyendo `WAKE_LOCK`, `DEVICE_ADMIN` (receptores), `BIND_ACCESSIBILITY_SERVICE`, `WRITE_SECURE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, `RECEIVE_BOOT_COMPLETED`, `KILL_BACKGROUND_PROCESSES`, `QUERY_ALL_PACKAGES` |

## Componentes principales (AndroidManifest)

| Componente | Rol |
|---|---|
| `de.ozerov.fully.FullyActivity` | Pantalla del kiosco: WebView a pantalla completa |
| `de.ozerov.fully.MainActivity` | Selector de app / ajustes / primer arranque |
| `de.ozerov.fully.TvActivity` | Variante Android TV (LEANBACK) |
| `de.ozerov.fully.ScreensaverActivity` | Protector de pantalla (reloj, URL, playlist, app externa) |
| `de.ozerov.fully.PinInputActivity` | Diálogo de PIN para salir del kiosco |
| `de.ozerov.fully.ProvisioningActivity` + `GET_PROVISIONING_MODE` | Aprovisionamiento como *device owner* (DPC) en Android 12+ |
| `de.ozerov.fully.MyAccessibilityService` | Bloquea barra de estado, notificaciones y diálogos del sistema |
| `de.ozerov.fully.TaskProtectorService` | Devuelve la app al frente cuando otra intenta taparla |
| `de.ozerov.fully.ForegroundService` / `NotificationService` | Servicio persistente y notificación |
| `de.ozerov.fully.remoteadmin.RemoteAdminService` | Administración remota por HTTP (LAN/nube) |
| `de.ozerov.fully.motiondetector.MotionDetector*` | Detección de movimiento por cámara y por micrófono |
| `de.ozerov.fully.receiver.BootReceiver` | Arranque automático tras reinicio |
| `de.ozerov.fully.MyDeviceAdmin` / `MyDeviceAdmin2` / `DeviceOwnerReceiver` | Políticas de administrador de dispositivo |

## Cómo implementa lo que te interesa

### 1. URL a pantalla completa

- `FullyActivity` aloja un `MyWebView` (WebView propio) dentro de un `WebTab` con barra de
  progreso y `SwipeRefreshLayout`.
- Pantalla completa por dos vías combinadas:
  - **Inmersivo**: ocultar barras del sistema y re-ocultarlas al recuperar foco.
  - **Ajustes**: `forceImmersive`, `removeNavigationBar`, `removeStatusBar` y
    `renderInCutoutArea` (dibujar sobre el notch).
- El manifest declara `android:windowSoftInputMode="adjustResize"` y `launchMode="singleTask"`,
  y la activity se excluye de recientes (`excludeFromRecents`, `autoRemoveFromRecents`).

### 2. Que la pantalla no se apague

En `res/xml/preferences.xml` (sección *Device Management*) existen las claves:

```
timeToScreenOffV2   keepScreenOn   keepScreenOnAdvanced   screenBrightness
```

- `keepScreenOn` → flag `FLAG_KEEP_SCREEN_ON` de la ventana (método simple, sin permisos).
- `keepScreenOnAdvanced` → WakeLock de pantalla (permiso `WAKE_LOCK`), pensado para cuando
  hay otras apps o el protector de pantalla encima. El propio APK incluye el permiso
  `WAKE_LOCK` y logs del tipo *"Failed to keep screen on by advanced method"*.
- `timeToScreenOffV2` / `screen_off_timeout` → cambio del timeout **del sistema** (requiere
  `WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS`, que la app también declara).
- `wakeupOnPowerConnect`, `screenOffOnPowerConnect`, `sleepSchedule`,
  `preventSleepWhileScreenOff`, `setWifiWakelock` completan la gestión de energía.

### 3. Bloqueo del dispositivo (kiosco real)

Evidencia de código en `defpackage.rz4` (clase base de actividades):

```java
public final void startLockTask() {
    super.startLockTask();
    this.C0 = true;
}

public final void stopLockTask() { ... }
```

Y en `FullyActivity.onResume()`:

```java
if (oi0.n(this) && this.P0.m() && this.E0.I1().booleanValue()) {
    startLockTask();
}
```

Es decir: **Lock Task Mode** de Android, condicionado a la configuración. Se apoya en:

- *Device owner* / DPC (`ProvisioningActivity`, `DeviceOwnerReceiver`, políticas MDM:
  `mdmLockTask*`, `mdmDisableStatusBar`, `mdmDisableKeyguard`, `mdmDisableADB`, etc.).
- *Accessibility service* para bloquear barra de estado y diálogos.
- Posibilidad de reemplazar el launcher (`LauncherReplacement` / `FakeLauncher` con
  categoría `HOME`).
- PIN de salida (`kioskPin`, `tapsToPinDialogInSingleAppMode`, `millisScreenOnToPinDialogInSingleAppMode`).
- Listas blanca/negra de apps y URLs (`kioskAppWhitelist`, `kioskAppBlacklist`,
  `allowed_start_URL`, `url_blacklisted`, `url_not_whistelisted`).
- Modo prueba de 60 s y protección Knox en equipos Samsung.

### 4. Otras funciones relevantes

Protector de pantalla (`timeToScreensaverV2`, `screensaverURL`, `screensaverBrightness`,
`screensaverPlaylist`), detección de movimiento por cámara/micrófono, sensores
(acelerómetro, brújula, proximidad), administración remota HTTP + MQTT + cloud, API JavaScript,
recarga al encender la pantalla (`reloadOnScreenOn`), orientación forzada, brillo,
silencio/volumen, NFC/QR, kiosco de video (RTSP), estadísticas de uso y logs.

## Traducción a nuestro proyecto

| Función de Fully | Kiosco Web (esta app) |
|---|---|
| URL única en WebView | ✅ `MainActivity` + WebView |
| Pantalla completa inmersiva | ✅ `WindowInsetsController` + flags legacy |
| Pantalla siempre encendida | ✅ `FLAG_KEEP_SCREEN_ON` + WakeLock opcional |
| Salir con PIN | ✅ 7 toques en la esquina + PIN (default 1234) |
| Ajustes en el equipo | ✅ Diálogo (URL, PIN, energía, orientación, arranque) |
| Bloqueo Lock Task | ✅ Opcional (pinning o device owner) |
| Arranque al reiniciar | ✅ `BootReceiver` (con límites de Android 10+) |
| Selector de apps / multi-app | ❌ Fuera de alcance: esta app es un kiosco web |
| Accessibility, Knox, MDM, remote admin, motion detection, screensaver | ❌ Pendiente / fuera del MVP |
