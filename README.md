# MultiAccount / MultiChat Manager — versión 1.3

Proyecto Android original actualizado. Se conserva Jetpack Compose, la pantalla de 30 espacios, WebView, filtros y la cola local de mensajes.

## Qué se corrigió

- El proyecto ahora tiene el directorio Gradle `app/` en la raíz del ZIP, para que `assembleDebug` y GitHub Actions encuentren `settings.gradle.kts`.
- Se añadió un botón **Auth** real a cada uno de los 30 espacios.
- Los espacios 1–15 usan el cliente de autorización nativo de Google Play Services para solicitar un token OAuth de Google con `youtube.readonly` (además de `openid`, `email` y `profile`). No se usa un WebView incrustado para iniciar sesión en Google.
- Los espacios 16–30 usan Twitch OAuth Device Code Grant. La app abre el navegador del sistema, muestra el código y espera la aprobación antes de guardar el token.
- Los tokens y metadatos se guardan por número de espacio en preferencias cifradas con AES-GCM y una clave de Android Keystore.
- Desconectar un espacio elimina únicamente su registro local e intenta revocar su token con el proveedor.
- Se corrigió `SecureAccountStore.get`: tenía un `return` dentro de una función con cuerpo de expresión, que impedía compilar Kotlin.
- La petición Twitch ahora manda `multipart/form-data`, como en los ejemplos oficiales del Device Code Grant, e incluye `scopes` también al canjear el código.
- Se aumentó `versionCode` a 2 y `versionName` a 1.2.

## Novedad 1.3: conectar pegando un token

Cada uno de los 30 espacios tiene ahora un botón **Pegar token** junto a **Auth**:

1. Pulsa **Pegar token** en el espacio que quieras (YouTube 1–15, Twitch 16–30).
2. Pega el token (o usa **Pegar del portapapeles**). Se acepta con o sin los prefijos `Bearer ` / `oauth:`.
3. Pulsa **Conectar**. La app valida el token contra el proveedor antes de guardarlo:
   - **Twitch**: `https://id.twitch.tv/oauth2/validate`; debe ser un token de *usuario* (se muestra el login).
   - **Google/YouTube**: `https://oauth2.googleapis.com/tokeninfo`; se muestra el canal y/o el correo si el token tiene permisos para ello.
4. Si el token es inválido o expiró, no se guarda nada y se muestra el motivo.

Notas:

- El token se guarda cifrado por espacio, igual que el flujo **Auth**.
- Al **Desconectar** un token pegado a mano solo se borra de la app; no se revoca en Google/Twitch (podrías estar usándolo en otro lado).
- La conexión por token no inicia sesión en el navegador integrado (cookies); eso sigue siendo independiente.
- Los tokens de Google duran poco (normalmente 1 hora). Al expirar, el espacio vuelve a verse como no conectado y hay que pegar uno nuevo.

## Configuración de Google / YouTube

1. En Google Cloud Console, crea o selecciona un proyecto.
2. Habilita **YouTube Data API v3**.
3. Configura la pantalla de consentimiento OAuth y añade las cuentas de prueba mientras la app esté en modo de prueba.
4. Crea un cliente OAuth de tipo **Android** con:
   - Nombre de paquete: `com.example.multichatmanager`
   - SHA-1 del certificado con el que firmarás el APK. Para un APK debug generado por GitHub Actions, obtén el SHA-1 del keystore debug de ese runner o configura un keystore de firma propio y estable.
5. En el consentimiento, configura el alcance `https://www.googleapis.com/auth/youtube.readonly` y los datos básicos de perfil/correo que solicite la app.

La app utiliza `AuthorizationClient` de Google Play Services. El token OAuth es de duración limitada; el usuario puede volver a pulsar **Auth** cuando expire. No se guarda un refresh token de Google ni se afirma que el token dure indefinidamente.

## Configuración de Twitch

1. Registra una aplicación en [Twitch Developer Console](https://dev.twitch.tv/console/apps).
2. Copia su **Client ID**. No se necesita ni se debe incrustar un Client Secret para el Device Code Grant de cliente público.
3. En GitHub, abre **Settings → Secrets and variables → Actions → Variables** y crea:
   - `TWITCH_CLIENT_ID` = Client ID de la aplicación.
4. Para compilar localmente, crea `gradle.properties` (no lo subas al repositorio) y agrega:

   ```properties
   TWITCH_CLIENT_ID=tu_client_id
   ```

El alcance solicitado actualmente es `user:read:email`, utilizado para validar y mostrar el usuario Twitch. Si más adelante se agregan funciones API adicionales, deben solicitarse únicamente los scopes que esas funciones necesiten.

## Compilar en GitHub Actions

1. Sube **el contenido de este ZIP** a la raíz del repositorio, no una carpeta contenedora adicional.
2. En **Settings → Secrets and variables → Actions → Variables**, agrega `TWITCH_CLIENT_ID`.
3. Abre la pestaña **Actions**.
4. Selecciona **Build Android APK** y pulsa **Run workflow**, o haz push a `main`.
5. Cuando termine, abre la ejecución y descarga el artefacto `MultiChatManager-debug`.

El workflow usa JDK 17 y Gradle 8.9. El archivo APK esperado es `app/build/outputs/apk/debug/app-debug.apk`.

## Compilar en Android Studio

1. Extrae el ZIP.
2. Abre la carpeta que contiene directamente `settings.gradle.kts` y `app/`.
3. Configura `TWITCH_CLIENT_ID` en `~/.gradle/gradle.properties` o en el `gradle.properties` local (no lo publiques).
4. Sincroniza Gradle y ejecuta la configuración `app`.

## Independencia y límites importantes

- Los 30 espacios tienen claves de almacenamiento distintas (`slot_1` … `slot_30`) y cada registro se cifra por separado. La clave AES está protegida por Android Keystore.
- Google puede permitir seleccionar la misma identidad Google en más de un espacio; la app conserva tokens separados por espacio, pero no puede garantizar que sean 15 personas/cuentas Google distintas.
- La independencia de cookies WebView solo existe cuando el Android System WebView del dispositivo anuncia soporte `MULTI_PROFILE`. La app muestra una advertencia cuando no lo soporta. No se debe asumir aislamiento de las sesiones web en esos dispositivos.
- El token de Google guardado es un token OAuth de acceso para el scope solicitado; no es un ID token ni un refresh token.
- Twitch Device Code Grant está pensado oficialmente para dispositivos con entrada limitada (por ejemplo, consolas). El flujo se implementó según el endpoint oficial y requiere Client ID.
- La cola de mensajes sigue siendo local: agregar, copiar y eliminar mensajes se conserva. Esta app no publica mensajes automáticamente.

## Estado de verificación

Verificado en el paquete fuente:

- ZIP válido y estructura de proyecto en raíz.
- 30 slots (1–15 YouTube, 16–30 Twitch) presentes en la interfaz.
- Acción Auth conectada al flujo Google o Twitch según el slot.
- Cola y persistencia de mensajes conservadas respecto al proyecto original.
- AndroidManifest XML bien formado.
- Error de Kotlin en `SecureAccountStore.get` corregido.

**No se pudo ejecutar una compilación Gradle en el entorno de edición**, porque no había Gradle/Android SDK instalado y el entorno no pudo resolver el host de descarga de Gradle. Por eso no se declara que el APK haya compilado ni que el login haya sido probado contra credenciales reales. La compilación y el consentimiento real requieren ejecutar el workflow y configurar los Client IDs/SHA-1 anteriores.

## Archivos principales

- `app/src/main/java/com/example/multichatmanager/MainActivity.kt`
- `app/src/main/java/com/example/multichatmanager/SecureAccountStore.kt`
- `app/src/main/java/com/example/multichatmanager/TwitchOAuth.kt`
- `app/src/main/java/com/example/multichatmanager/TokenConnector.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`
- `.github/workflows/android.yml`
