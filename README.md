# BeamCast

BeamCast es un proyecto original, inspirado en la categoría de apps que permiten navegar por la web y enviar vídeos a la TV. No está afiliado ni respaldado por ninguna otra aplicación.

El teléfono detecta los vídeos de las páginas que visitas (o los archivos locales que elijas) y los envía a un receptor en la TV, que los reproduce con ExoPlayer.

## Módulos

| Módulo    | Descripción                                                                                  |
|-----------|----------------------------------------------------------------------------------------------|
| `:protocol` | Kotlin puro. Protocolo JSON compartido entre el emisor y el receptor.                      |
| `:mobile`   | App Android `com.beamcast.sender`: navegador, detección de vídeos, envío a la TV, bookmarks, historial y control remoto. |
| `:tv`       | App Android TV `com.beamcast.receiver`: servidor HTTP, descubrimiento NSD y reproducción (MP4, HLS, DASH, imágenes). |

## Cómo funciona

1. El receptor (`:tv`) arranca un servidor HTTP en el puerto `8765` y se anuncia por mDNS con el servicio `_beamcast._tcp`.
2. El emisor (`:mobile`) busca ese servicio en la misma red Wi-Fi.
3. El emisor envía comandos en JSON por HTTP. El receptor responde con su estado de reproducción.

## Protocolo (v1)

Todos los cuerpos son JSON UTF-8. Los errores devuelven un código no 2xx con un `ErrorResponse`.

| Método | Ruta       | Petición       | Respuesta        |
|--------|------------|----------------|------------------|
| GET    | `/info`    | —              | `DeviceInfo`     |
| GET    | `/status`  | —              | `PlaybackStatus` |
| POST   | `/load`    | `LoadRequest`  | `PlaybackStatus` |
| POST   | `/control` | `ControlRequest` | `PlaybackStatus` |

Las acciones de `ControlRequest` son `play`, `pause`, `stop`, `seek`, `seekRelative` y `volume`.

## Compilar

Requiere JDK 17 o superior (el CI usa JDK 21) y el Android SDK con la plataforma 35.

```bash
./gradlew :mobile:assembleDebug :tv:assembleDebug
```

Los APK se generan en:

- `mobile/build/outputs/apk/debug/`
- `tv/build/outputs/apk/debug/`

## Instalar

Teléfono:

```bash
adb install -r mobile/build/outputs/apk/debug/<archivo>.apk
```

TV (por ADB por red):

```bash
adb connect <ip-de-la-tv>
adb -s <ip-de-la-tv>:5555 install -r tv/build/outputs/apk/debug/<archivo>.apk
```

## Uso

1. Conecta el teléfono y la TV a la misma red Wi-Fi.
2. Abre BeamCast en la TV y espera a que aparezca el receptor.
3. En el teléfono, abre BeamCast y navega a una página con vídeo.
4. Pulsa el vídeo detectado y elige enviarlo a la TV.
5. Usa la pantalla de control remoto para pausar, avanzar o ajustar el volumen.
6. Para enviar un archivo local, elígelo desde la pantalla de archivos.

## Limitaciones

- Los dos dispositivos deben estar en la misma red local.
- Los flujos protegidos con DRM no son compatibles.
- Algunos sitios vinculan el vídeo a la sesión o a la IP del teléfono; en esos casos la TV puede no reproducirlo.
- Las APK de depuración no están firmadas para distribución; una build de release requiere su propia firma.

## Hoja de ruta

- Destinos adicionales: Chromecast, DLNA y Roku.
- Selector de subtítulos.
- Listas de reproducción y soporte IPTV.
- Cola de reproducción.
