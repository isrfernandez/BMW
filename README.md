# Cockpit F30

Lanzador y panel de datos con estética BMW para la radio Wondefoo **ZQ9109** (12,3", plataforma TopWay/MediaTek) en un BMW F30 con decodificador CAN Raise.

> Proyecto inicial (v0.1). La lógica del protocolo Raise está escrita para **ajustarse con datos reales del coche**: el formato de trama y los códigos de tipo vienen de pistas sin confirmar.

## Qué hace

- **Interfaz estilo iDrive 8** a pantalla completa (pensada para 1920×720):
  - Barra lateral fija a la izquierda (Home, Navegación, Música, Apps, Ajustes) con objetivos de 88 dp.
  - Inicio con cuadro estilo iDrive 8 (unos 410 dp de alto): escalas en chevron azul (velocidad, no lineal 0–250) y rojo (rpm 1–8, zona roja desde 6) con las cifras por fuera, barra luminosa que sube por el borde, velocidad y marcha en grande, combustible y aceite, lista de estado sobre franjas M, y línea inferior con hora, autonomía, coche y temperatura exterior. Todo dibujado en Compose, sin gráficos de BMW. Marcha, combustible, aceite y autonomía son campos nuevos de `VehicleState` (solo Demo los rellena por ahora; con datos reales salen "--").
  - Debajo, parrilla panorámica de tarjetas que se desplaza con la ruleta: Música, Navegación, Datos de viaje, Neumáticos (sin datos aún: "--"), Vehículo, Teléfono y Diagnóstico.
  - Cada tarjeta lleva una ilustración tipo cristal dibujada en Compose con el color de su función (`CardArt.kt`): ecualizador y nota (Música), mapa con ruta y pin (Navegación), gráfico (Datos de viaje), vista cenital (Neumáticos), foto trasera (Vehículo), auricular con ondas (Teléfono) y pulso con llave (Diagnóstico).
  - Panel central del cuadro con la escena del coche visto desde atrás (`res/drawable-nodpi/car_rear_scene.webp`, recortada de una imagen generada por el usuario) fundida con viñeta; encima, presión de las 4 ruedas y avisos de puertas, maletero y capó abiertos. Velocidad y marcha en pestañas oscuras.
  - Pantalla Vehículo con la vista cenital del F30 (`res/drawable-nodpi/car_top.png`, recortada de una imagen generada por el usuario) con halo azul, presión de cada rueda, autonomía y puertas abiertas en rojo sobre la imagen. Y un cajón de apps.
  - Paleta grafito `#0B0C10`, azul BMW `#1C69D8`, rojo M `#E10600`; tarjetas de cristal con radio de 12 dp. Fuentes **Saira** para cifras e **Inter** para textos (ambas SIL Open Font License, en `res/font`).
- Se puede elegir como **lanzador** (pantalla de inicio) de la radio.
- **Ruleta iDrive**: todo se maneja por foco. La ruleta mueve el foco adelante/atrás, el joystick (DPAD) lo mueve en la cuadrícula y pulsar abre.
- **Servicio en primer plano** (`VehicleService`) que sigue leyendo el coche con Maps o Spotify abiertos encima. Notificación silenciosa con prioridad mínima. Arranca solo al encender.
- **Exclusión de la optimización de batería**: la pide una vez al primer arranque y desde Diagnóstico.
- **Tres fuentes de datos**, elegibles en Diagnóstico:
  - *Serie*: lee el UART `/dev/ttyMT*` y decodifica tramas Raise.
  - *CarProperty*: `CarPropertyManager` si la radio es Android Automotive (poco probable).
  - *Demo*: datos simulados para probar la interfaz sin coche.
  - *Auto*: CarProperty si existe, si no Serie.
- **Pantalla de Diagnóstico** para investigar la placa: puertos serie encontrados y sus permisos, baudios, tramas en crudo (hex), errores de checksum y **cada evento de tecla/ruleta** que llega.

## Compilar e instalar

1. Abre la carpeta en **Android Studio** (Ladybug o posterior). Descargará el SDK 34 y las dependencias.
2. Conecta la radio por USB o ADB por Wi‑Fi (Ajustes → Opciones de desarrollador; en TopWay suele estar tras una contraseña de fábrica).
3. `Run ▶` o `./gradlew installDebug`.
   Para el coche, mejor la versión no depurable (`./gradlew assembleRelease`, queda en `app/build/outputs/apk/release/`): va más fluida y lleva la misma firma, así que se instala encima de la de depuración.
4. Pulsa Inicio y elige **Cockpit F30** como lanzador si quieres que sea la pantalla principal.

## Primeros pasos en el coche

1. Abre **Diagnóstico** y elige *Demo* para comprobar la interfaz y que la ruleta mueve el foco.
2. Gira y pulsa el iDrive y mira la lista de *Eventos de entrada*. Si la ruleta no mueve el foco, ahí verás qué manda (código de tecla, fuente, eje) para ajustar `input/IDriveInput.kt`.
   Si algún botón del iDrive manda una tecla rara, en *Ruleta iDrive: enseñar botones* toca la función (Abajo, OK, Atrás…) y pulsa ese botón: queda guardado y no hace falta recompilar.
3. En *Puerto serie*, «Probar todos los puertos» intenta abrir cada `/dev/tty*` y escucha 2 s: dice si hay permiso y cuántos bytes llegan. En *Bytes en crudo* sale todo lo que se lee, aunque no sean tramas Raise.
4. En *Apps del CAN / MCU*, «Buscar apps del coche» lista las apps del fabricante (canbus, MCU…) y las acciones de broadcast que declaran; «Escuchar sus broadcasts» apunta los que lleguen al abrir una puerta o arrancar.
5. «Compartir informe» o «Guardar informe en Descargas» junta todo en un .txt.
3. Elige *Serie* y prueba cada puerto `ttyMT*`. Si sale `rw` en el puerto y aparecen tramas `2E …` al arrancar, acelerar o abrir puertas, el formato coincide. Anota qué bytes cambian y corrige `vehicle/raise/RaiseBmwDecoder.kt`.

## Correcciones a las pistas de partida

- **El permiso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` no excluye la app por sí solo**: solo permite mostrar el diálogo, y el usuario tiene que aceptarlo. La app lo hace.
- **La ruleta como `SOURCE_ROTARY_ENCODER` usa `AXIS_SCROLL`, no `AXIS_VSCROLL`**. `AXIS_VSCROLL` es la rueda de ratón. La app atiende ambos, más TAB y DPAD.
- **El parser de ejemplo leía posiciones fijas sin comprobar longitud ni checksum.** Aquí se usa el formato que Raise usa en otros modelos (`2E | tipo | longitud | datos | checksum`, checksum = suma de tipo+longitud+datos XOR 0xFF) y se descartan las tramas inválidas. Las posiciones del ejemplo (RPM en datos[1..2], velocidad en datos[3], puertas en datos[1]) se han mantenido, pero **no están verificadas**.

## Limitaciones y dudas abiertas

- **Acceso a `/dev/ttyMT*`**: una app normal casi nunca puede abrirlo (permisos y SELinux). Probablemente haga falta root o instalarla como app de sistema.
- **Conflicto con `McuService`**: si el servicio de TopWay ya tiene abierto el puerto, leer a la vez reparte los bytes entre los dos y ambos verán tramas rotas. La lectura serie sirve para investigar; para uso diario la vía buena es la API de TopWay.
- **API de TopWay (`McuService`)**: no es pública y no está implementada todavía. Siguiente paso: con ADB, `adb shell service list | grep -i mcu`, `adb shell dumpsys activity services | grep -i mcu` y extraer los APK del sistema que la usan para ver sus métodos o broadcasts. Con eso se añade una cuarta fuente sin tocar la interfaz.
- **Baudios**: 38400 es el valor habitual de Raise, sin confirmar. Se fijan con `stty`, que necesita root.
- **Logos y tipografía**: no se incluyen el logotipo ni la fuente oficial de BMW (marcas registradas); la estética es una inspiración con colores y franjas M.

## Estructura

```
app/src/main/java/com/cockpitf30/
├── CockpitApp.kt                 canal de notificación
├── service/VehicleService.kt     servicio en primer plano, elige la fuente
├── service/BootReceiver.kt       arranque automático
├── vehicle/VehicleRepository.kt  estado compartido servicio ↔ interfaz
├── vehicle/raise/                tramas Raise y su traducción  ← ajustar con datos reales
├── vehicle/source/               Serie, CarProperty, Demo
├── input/IDriveInput.kt          mapeo de ruleta/teclas        ← ajustar con Diagnóstico
├── system/                       ajustes y optimización de batería
└── ui/                           Compose: tema, relojes, pantallas
```

Tests: `./gradlew test` (decodificador Raise).
