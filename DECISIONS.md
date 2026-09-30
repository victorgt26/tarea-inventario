# Decisiones de implementación

## Alcance y arquitectura

Se implementa el contrato existente sin modificar `com.store.inventory.api` ni la firma de `Inventory.create`. No se añaden dependencias de ejecución.

Se adopta la organización solicitada: dominio como modelos de datos, reglas de negocio en los casos de uso de aplicación y separación explícita de entrada/salida.

```text
inventory/
├── domain/                         Solo datos, sin reglas ni transiciones
│   ├── InventoryState              Colecciones de productos y pedidos
│   ├── StockProduct                Datos inmutables de existencias
│   ├── OrderReservation            Reserva del contrato y su estado
│   ├── ReservationStatus           ACTIVE, CONFIRMED, EXPIRED
│   ├── ReservationPolicy           Duración y límite
│   └── LowStock                    Datos de un aviso
├── application/
│   ├── port/
│   │   ├── in/InventoryUseCase
│   │   └── out/
│   │       ├── InventoryPort
│   │       └── StockAlertPort
│   └── usecase/
│       ├── InventoryUseCases       Validaciones, reservas, caducidad y avisos
│       └── ReservationPolicies     Configuración de reglas por categoría
├── infrastructure/
│   └── adapters/
│       ├── in/InventoryServiceAdapter
│       └── out/
│           ├── persistence/InMemoryInventoryRepository
│           └── notification/ListenerStockAlertAdapter
├── api/                            Contrato original, sin modificaciones
└── Inventory                       Raíz de composición
```

La arquitectura hexagonal admite modelos de dominio con comportamiento o modelos de datos. Aquí se elige explícitamente la segunda alternativa: los modelos no validan ni cambian estados. Los records contienen datos inmutables; `InventoryState` proporciona las colecciones mediante getters. Solo los casos de uso modifican dichas colecciones durante la operación exclusiva del repositorio.

`InventoryUseCases` implementa los casos de registro, reposición, reserva, confirmación y disponibilidad, con auxiliares privados para expiración y avisos. Todas las validaciones, cálculos de existencias, reglas de idempotencia y transiciones residen en `application.usecase`. `ReservationPolicies` proporciona las políticas por defecto y los casos de uso validan su configuración al construir la aplicación.

No se duplican categorías, reservas ni excepciones: se reutilizan los tipos existentes de `api`. `OrderReservation` añade únicamente el estado de procesamiento a `api.Reservation`.

Para expresar físicamente los paquetes `application.port.in` y `application.port.out` sin copiar el contrato, `InventoryUseCase` extiende `api.InventoryService` y `StockAlertPort` extiende `api.StockAlertListener`, sin redeclarar métodos. Son extensiones nominales para identificar la dirección de los puertos, no contratos paralelos con modelos o firmas diferentes. `InventoryPort` es el puerto de salida definido por la aplicación para acceder al estado de inventario de forma atómica. La aplicación depende de este contrato; `InMemoryInventoryRepository`, en infraestructura, lo implementa.

Los adaptadores se ubican en `infrastructure.adapters.in` y `infrastructure.adapters.out`. El adaptador de entrada delega en el puerto de casos de uso. Los adaptadores de salida implementan el acceso al estado en memoria y la conexión con el listener proporcionado. No contienen reglas de negocio ni conversiones de modelos. Estos adaptadores delgados mantienen explícitos los bordes pedidos, aunque en este ejercicio sus delegaciones sean sencillas.

Las dependencias apuntan hacia dentro: infraestructura utiliza los puertos de aplicación y los datos de dominio; aplicación utiliza dominio y el contrato compartido. Dominio no importa aplicación ni infraestructura. `Inventory.create` compone las piezas y conserva su firma pública.

Añadir una categoría requiere evolucionar el enum público y registrar su política. No requiere otro enum, conversiones ni condicionales de categoría en la operación de reserva.

## Definiciones de arquitectura y responsabilidades

- **Puerto de entrada (`application.port.in`)**: contrato de las operaciones que la aplicación ofrece. `InventoryUseCase` reutiliza `InventoryService`; no accede a tecnologías ni contiene implementación.
- **Puerto de salida (`application.port.out`)**: contrato de una capacidad que la aplicación necesita. `InventoryPort` exige acceso exclusivo al estado; `StockAlertPort` permite comunicar avisos. Los define la aplicación para depender de abstracciones y no del almacenamiento o del canal concretos.
- **Adaptador de entrada (`infrastructure.adapters.in`)**: recibe llamadas del consumidor del contrato público y las dirige al puerto de entrada. `InventoryServiceAdapter` no incorpora validaciones de negocio.
- **Adaptador de salida (`infrastructure.adapters.out`)**: implementa un puerto usando un mecanismo concreto. `InMemoryInventoryRepository` implementa el almacenamiento sincronizado y `ListenerStockAlertAdapter` delega la entrega al listener recibido.
- **Caso de uso (`application.usecase`)**: ejecuta las reglas y coordina los puertos. `InventoryUseCases` agrupa las cinco operaciones del contrato para compartir el tratamiento de caducidades, idempotencia y avisos. Si sus responsabilidades crecen de forma independiente, se pueden separar por operación conservando el puerto público.
- **Modelo de dominio (`domain`)**: representa datos y estados. En esta solución no contiene validaciones, reglas, acceso a puertos ni lógica de persistencia. Los records son objetos Java de datos sin framework; no es necesario convertirlos en clases con setters.
- **Raíz de composición (`Inventory`)**: construye y conecta implementaciones y puertos. Es el único lugar que elige los adaptadores concretos para la fábrica pública.

Flujo de una reserva:

```text
Cliente → InventoryServiceAdapter → InventoryUseCase → InventoryUseCases
                                                        │
                                                        ├─ InventoryPort
                                                        │  └─ InMemoryInventoryRepository
                                                        │     (operación exclusiva sobre InventoryState)
                                                        │
                                                        └─ StockAlertPort
                                                           └─ ListenerStockAlertAdapter
                                                              └─ StockAlertListener proporcionado
```

El aviso se invoca después de terminar la operación de inventario. Este diagrama representa llamadas en ejecución; las dependencias de código van desde los adaptadores hacia los puertos y no al revés.

El sufijo `Port` identifica la abstracción de aplicación. El sufijo `Repository` se reserva aquí para el adaptador concreto `InMemoryInventoryRepository`. No es una restricción universal de arquitectura hexagonal, sino la convención elegida para este proyecto.

Se acepta compartir el paquete `api`, sin dependencias tecnológicas, con las capas internas. No se persigue un dominio aislado de esos tipos a costa de duplicar categorías, DTOs o excepciones. Los puertos que extienden interfaces existentes tampoco crean representaciones paralelas.

## Principios y decisiones de diseño

- **Responsabilidades separadas**: datos en dominio, reglas en casos de uso, mecanismos técnicos en adaptadores y composición en la fábrica.
- **Inversión de dependencias e inyección por constructor**: la aplicación recibe puertos, reloj y políticas; no construye adaptadores. Las pruebas de casos de uso usan un doble de `InventoryPort` sin infraestructura.
- **Adapter y Ports and Adapters**: las implementaciones de infraestructura conectan el contrato de entrada y las capacidades de salida con la aplicación.
- **Políticas parametrizadas**: duración y límite son datos de un mapa inmutable, no una jerarquía de estrategias por categoría. Cambiar sus valores no requiere cambiar el algoritmo de reserva.
- **Idempotencia y estados explícitos**: los reintentos se resuelven con un identificador único de pedido y estados terminales, sin duplicar descuentos ni resucitar pedidos caducados.
- **Simplicidad**: no se introducen Spring, ORM, bus de eventos ni herencia de entidades para un servicio en memoria. La separación de paquetes no implica que existan módulos Java independientes o restricciones impuestas por el compilador entre capas.

Los mapas de `InventoryState` son mutables y sus getters permiten acceso directo. El puerto restringe ese acceso al callback por contrato, no mediante una copia defensiva. Los adaptadores y futuros casos de uso deben respetarlo: conservar una referencia y modificarla después rompería la garantía de concurrencia.

## Reglas de categoría y estados

| Categoría | Vigencia | Máximo por pedido |
| --- | --- | --- |
| `STANDARD` | 15 minutos | Sin límite comercial |
| `PRE_ORDER` | 24 horas | Sin límite comercial |
| `FLASH_SALE` | 5 minutos | 2 unidades |

«Sin límite comercial» está sujeto al tipo `int` del contrato y a las existencias. La capacidad máxima admitida por SKU es `Integer.MAX_VALUE`, incluyendo las unidades reservadas.

| Estado actual | Operación o condición | Resultado |
| --- | --- | --- |
| Sin pedido | Reserva válida | `ACTIVE`; se reduce disponibilidad |
| `ACTIVE` | Reintento con los mismos datos | Misma reserva y vencimiento |
| `ACTIVE` | Confirmación antes del vencimiento | `CONFIRMED`; se descuentan existencias físicas |
| `ACTIVE` | Instante igual o posterior al vencimiento | `EXPIRED`; se devuelve disponibilidad |
| `CONFIRMED` | Reintento o confirmación repetida | Éxito sin nuevos efectos |
| `EXPIRED` | Reintento o confirmación | `IllegalStateException`; no se reactiva |
| Cualquier pedido existente | Reintento con otro SKU o cantidad | `IllegalArgumentException` |

## Invariantes y concurrencia

Las unidades disponibles se descuentan al reservar. Confirmar descuenta las unidades físicas del almacén, pero no vuelve a descontar disponibilidad. Caducar devuelve disponibilidad una sola vez. Las unidades confirmadas nunca caducan.

El bloqueo cubre en una sola operación la lectura del reloj, expiraciones, unicidad global del pedido, comprobación de existencias y modificación. Evita sobreventa y dobles reservas dentro de una instancia, incluso entre distintos SKU. No hay una secuencia de lectura/escritura del repositorio expuesta al cliente. Se comprueba la capacidad antes de sumar: las unidades físicas incluyen las reservadas, para que una futura caducidad tampoco desborde `int`.

`InventoryPort` proporciona exclusión mutua, no rollback genérico de un callback arbitrario. Los casos de uso validan antes de aplicar su modificación; el procesamiento de caducidades puede persistir aunque la solicitud termine rechazada. No se permite retener el estado fuera del callback.

## Supuestos explícitos

1. `orderId` identifica globalmente un pedido de un solo producto. Repetir SKU y cantidad devuelve la reserva original, sin consumir más unidades ni extender la fecha. Cambiar los parámetros del mismo ID produce `IllegalArgumentException`.
2. Una confirmación repetida tiene éxito sin efectos adicionales. También se devuelve la reserva original al repetir un pedido ya confirmado, incluso después de su fecha original de caducidad.
3. Un pedido caducado no se reactiva: reservarlo o confirmarlo produce `IllegalStateException`. El cliente debe crear otro ID. Así, un paquete retrasado no aparta unidades de nuevo inadvertidamente. Los intentos rechazados sin reserva no consumen el ID.
4. La reserva caduca en `now >= expiresAt`. Se utiliza exclusivamente el `Clock` inyectado. La caducidad se materializa en `available`, `addStock`, `reserve` y `confirm`, sin hilos ni temporizadores; `registerProduct` no necesita procesarla. Las unidades caducadas están disponibles para la siguiente operación aunque no haya habido consultas intermedias. Una caducidad ya materializada es definitiva, incluso si el reloj retrocede.
5. Registrar de nuevo el mismo SKU y categoría es idempotente. Cambiar la categoría se rechaza para no alterar pedidos existentes. No se recortan ni normalizan identificadores; nulos o en blanco se rechazan. Cantidades no positivas se rechazan.
6. Un SKU desconocido tiene disponibilidad cero; reservarlo produce `InsufficientStockException`. Reponerlo requiere registrarlo previamente.
7. Se avisa al reservar o reponer si quedan como máximo cinco unidades. Registrar un producto vacío no dispara aviso porque todavía no se ha cargado inventario. Cada reposición exitosa inicia un nuevo ciclo de avisos, aunque la cantidad disponible continúe por debajo del umbral. La caducidad no rearma avisos. Consultas, confirmaciones y reintentos tampoco generan avisos nuevos.
8. El reloj, los puertos, la categoría y la configuración no pueden ser nulos; esos errores de programación producen `NullPointerException`. Las políticas ausentes o con duración/límite inválidos se rechazan al construir los casos de uso.
9. El aviso contiene la disponibilidad en el instante de la transición. Puede diferir de la disponibilidad al recibirlo si hay operaciones concurrentes.

## Notificaciones y fallos

La decisión de emitir un aviso se registra bajo el mismo bloqueo que la reserva/reposición. La invocación del listener ocurre después de liberar el bloqueo, permitiendo otras operaciones y consultas desde el listener.

Se realiza un solo intento por ciclo. Una `RuntimeException` del listener se registra y no revierte ni hace parecer fallida una reserva ya aplicada. No se ofrece entrega garantizada: un proceso que termina entre modificar el estado y notificar puede perder el aviso. Un listener lento retrasa la respuesta de su llamada, aunque no bloquea el inventario. Los avisos concurrentes de distintos ciclos pueden recibirse fuera de orden. Estas limitaciones son deliberadas y requieren un outbox antes de producción.

## Fuera del ejercicio y paso a producción

- El almacenamiento es volátil, por instancia. **No soporta varias instancias compartiendo inventario.** Migraría a tablas de productos, reservas y eventos, con restricción única por pedido y transacciones que coordinen existencias, expiraciones y estado de alerta. Usaría bloqueo de filas o actualizaciones condicionales/versionadas, con orden consistente de bloqueos y pruebas de integración de conflictos.
- El puerto actual opera sobre un estado completo: sirve para aislar esta implementación, pero no es una abstracción SQL eficiente. Al migrar lo refinaría hacia operaciones transaccionales por producto/pedido, manteniendo las invariantes y las pruebas de contrato; no cargaría todo el inventario ni mantendría un bloqueo global en base de datos.
- Los avisos se persistirían en un **outbox transaccional** con el cambio de inventario. Un worker efectuaría reintentos con backoff, métricas y cola de fallos. Los consumidores deduplicarían por identificador de evento/ciclo; no se prometería entrega exactamente una vez por correo.
- La expiración actual recorre los pedidos retenidos, O(n) por operación que procesa vencimientos; registrar un producto no hace ese recorrido. Para volumen real, añadiría índices por estado/fecha y procesamiento por producto o lotes, usando una fuente de tiempo consistente entre instancias. Un worker puede limpiar en segundo plano, pero las operaciones deben seguir comprobando el vencimiento.
- Se conservan los IDs confirmados y caducados indefinidamente para deduplicar. Definiría una ventana de idempotencia, retención y archivado antes de limitar ese almacenamiento.
- Quedan fuera HTTP, autenticación, pagos, envío real de correos, cancelaciones, actualización de categorías y atomicidad de un carrito de varios productos: no están en el contrato proporcionado.

## Validación y revisión

Ejecutar `mvn clean test` con Java 21 y Maven. El POM fija `maven-compiler-plugin` 3.13.0, `maven-surefire-plugin` 3.2.5 y compilación con `release=21`, para no depender de las versiones de plugins que elija cada instalación de Maven. Las pruebas originales se conservan. Las pruebas directas de los casos de uso verifican validaciones, estados terminales, idempotencia y configuración sin pasar por los adaptadores de infraestructura. Las pruebas de comportamiento usan un reloj controlable, sin esperar minutos reales, y verifican las tres políticas, el instante exacto de caducidad, confirmación e idempotencia, conflictos, límites, alertas, validaciones, capacidad numérica, fallo/reentrada del listener y peticiones concurrentes.

La prueba de fallo del listener genera deliberadamente un WARNING. No implica un fallo de la suite. La concurrencia se prueba con un grupo de hilos y una barrera de inicio; los resultados comprueban tanto las reservas aceptadas como el stock final.

### Correspondencia entre requisitos y pruebas

| Requisito o riesgo | Evidencia en la suite |
| --- | --- |
| Reservar aparta unidades y rechaza sobreventa | `InventoryServiceTest` y `concurrentOrdersCannotOversell` |
| Duración por categoría y frontera exacta | `categoryDeadlineAndExactExpiry` (tres categorías) |
| Liberación sin una consulta previa | `expiryIsAppliedByReserveAndConfirmWithoutAnAvailabilityRead` |
| Unidades confirmadas permanecen vendidas | `confirmedUnitsStaySold`, `retriesKeepOriginalDeadlineAndConfirmedOrdersStaySold` |
| Reintentos simultáneos del mismo pedido | `concurrentDuplicateOrdersOnlyReserveOnce` |
| Conflictos de datos, límite y configuración | `conflictsDoNotModifyStock`, `categoryLimitAndFailedRequestCanBeRetried`, `InventoryUseCasesTest` |
| Avisos sin repetición hasta reponer | `alertsAreRearmedOnlyByReplenishment` |
| Fallo del canal y ausencia de bloqueo durante el aviso | `listenerFailureDoesNotInvalidateCommittedReservation`, `listenerRunsOutsideRepositoryLock` |
| Cantidades inválidas, productos desconocidos y desbordamiento | `invalidInputUnknownProductsAndOverflow` |

La revisión final ejecutó **20 pruebas con Java 21**, sin fallos ni errores. Incluye las tres pruebas originales, trece ejecuciones de comportamiento y cuatro pruebas directas de casos de uso. Se comprobaron también las declaraciones de paquetes y las dependencias compiladas con `jdeps`: dominio no depende de aplicación/infraestructura y aplicación no depende de infraestructura. Estas verificaciones de arquitectura fueron realizadas durante la revisión; no constituyen una regla automatizada incorporada a Maven.

No se afirma cobertura exhaustiva de todos los intercalados concurrentes, rendimiento bajo carga ni entrega de notificaciones en sistemas distribuidos. Esas verificaciones requieren la futura implementación persistente y sus pruebas de integración.
