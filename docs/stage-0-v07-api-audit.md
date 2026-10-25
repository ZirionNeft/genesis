# 0A / D13: идентичность NPC, смена роли и native store

Дата: **25.10.2026**. **0A IN_PROGRESS, D13 OPEN**. Это чтение закреплённого
артефакта/ассетов, два codec-теста и подготовка read-only стенда.
Живой сервер/клиент в этом пакете не запускались. Принятие NPC, безопасная
передача управления и долговечность store ещё не приняты. D01 не переоткрывается.

Позднее 25.10 выполнены [живой запуск 0.7.2](stage-0-v07-live-probe.md) и
[переход/запуск 0.7.4](hytale-0.7.4.md). Ниже сохранён исходный статический аудит
0.7.2. Исследованные классы API и массив species совпали на 0.7.4;
новый инвентарь/клиентские TODO — в документе перехода.

## Источники и воспроизведение

Maven `com.hypixel.hytale:Server:0.7.2` и установленный HytaleServer.jar имеют
одинаковый SHA-256 `23f71f03763422ce7c04df191db49cd0e7d87d43b12baad75c43fdc202410aff`;
revision `d769dc3d57881261a62858e1394f0e4de9a21eca`. Использован установленный
Assets.zip размером 3 942 760 463 байта из того же локального release-пакета.
Полный хеш Assets.zip здесь не назначался; хеши выбранных файлов есть в отчёте.

Сигнатуры/байткод прочитаны `javap -p -c` (инструмент javap 27; это только
дизассемблер). Проект и тесты компилируются/исполняются на **JDK 25**, без preview.
Машинные пути в Gradle не добавлялись. Воспроизведение файлового инвентаря:

```sh
python3 tools/audit_hytale_v07_assets.py \
  --assets /absolute/path/to/Assets.zip \
  --server-jar /absolute/path/to/HytaleServer.jar \
  --expected-server-version 0.7.2 \
  --output docs/stage-0-v07-vanilla-inventory.json
```

[Скрипт](../tools/audit_hytale_v07_assets.py) читает входы и пишет только явно
выбранный отчёт. Это dev-инвентарь, не runtime allowlist и не интерпретатор NPC DSL.
[JSON-отчёт](stage-0-v07-vanilla-inventory.json) содержит 12 видов / 16 кандидатов
ролей, пути и SHA-256 role/model/непосредственно заданных model/texture ресурсов,
наличие непосредственно объявленных клипов. Parent/inherited animations не
разворачиваются; пустые списки дочернего файла не означают отсутствие анимаций.
Статический источник не доказывает, что asset загружается или правильно играет.

## Матрица исходных видов

Все строки имеют runtime eligibility **UNVERIFIED**. Виды не включены для приручения.
Начальный возраст Genesis у принятого ванильного NPC остаётся **UNDECIDED**;
существование отдельного Foal/Calf/Piglet ассета не является возрастным датчиком.

| Исходный вид | Найденные кандидаты Role ID | Непосредственный vanilla TameRoleChange |
| --- | --- | --- |
| wolf | Wolf_Black, Wolf_White | Не задан в этих файлах |
| fox | Fox | Не задан |
| hyena | Hyena | Не задан |
| horse | Horse | Tamed_Horse |
| deer | Deer_Doe, Deer_Stag | Не задан |
| antelope | Antelope | Не задан |
| bison | Bison | Tamed_Bison |
| boar | Boar | Tamed_Boar |
| mosshorn | Mosshorn, Mosshorn_Plain | Tamed_Mosshorn / Tamed_Mosshorn_Plain |
| raven | Raven | Не задан |
| owl | Owl_Brown, Owl_Snow | Не задан |
| hawk | Hawk | Не задан |

Параметры роли могут вычисляться и наследоваться. Например, Wolf_Black задаёт
Appearance через параметр, Deer_Stag использует model ID **Model_Deer_Stag**.
Механическое равенство species/role/model ID ошибочно. Wolf_White, Owl_Snow и
Mosshorn_Plain используют parent model; у Deer_Stag часть clips задана локально.
Наличие Tamed_* не разрешает Genesis перехватывать уже прирученное существо.
Идентификация rig/креплений, всех наследованных моделей/клипов и происхождения
конкретного живого NPC остаётся отдельной проверкой.

## Постоянный ключ и ECS

- `UUIDComponent.CODEC` сохраняет поле `UUID` через `Codec.UUID_BINARY`;
  `EntityModule.setup()` регистрирует компонент под сохраняемым ID `UUID`.
  `getUuid()` — чтение. Это кандидат на постоянную идентичность, не сетевой ID.
- `UUIDComponent` afterDecode генерирует новый version-3 UUID, если поле отсутствует.
  Поэтому успешный decode сам по себе не доказывает сохранение старого животного.
  В диагностике запрещены ensure/add/randomUUID для восполнения отсутствующего ID.
- `WorldConfig` имеет сохраняемый UUID; `World.getWorldConfig().getUuid()` даёт
  вторую часть ключа. `EntityStore.getRefFromUUID(uuid)` ищет активный Ref.
  Ref/index/networkId не являются постоянным ключом. Перенос/копирование мира
  и привязку provider/store нужно определить в 2A.
- `Store.isInThread()/assertThread()` и `World.execute(Runnable)` существуют.
  `AbstractPlayerCommand.executeAsync()` передаёт callback через World как Executor;
  защищённый `execute(...store,player,playerRef,world)` — подтверждённый путь
  явного технического осмотра в потоке мира. Disk/network/future wait там не нужны.

Тесты `HytaleIdentityCodecTest` подтвердили binary UUID roundtrip и генерацию
разных новых UUID при двух decode пустого документа. Это проверки codec,
**не** save/load/restart существующего ванильного NPC. D01 ранее принимал
рестарт технического AnatomyProbe; новую vanilla-приёмку это не закрывает.

## Смена роли меняет представление ECS

В `RoleChangeSystem.tick()` байткод вызывает `Store.removeEntity(ref, UNLOAD)`
(offset 65), получает Holder с прежним NPCEntity, очищает role и ряд компонентов
(messaging, timers, evaluator, value store, repulsion), меняет role metadata,
затем `Store.addEntity(holder, LOAD)` (offset 258) и получает новый Ref.
Это использование того же Holder, а не показанный путь клонирования NPC.
Сохранность UUID/своих компонентов и единственность активного представления
надо проверить в runtime; прежний Ref после перехода сохранять нельзя.

UNLOAD/LOAD здесь не доказывают выгрузку чанка или рестарт. Они также не являются
смертью/рождением/приручением. Реакция Genesis обязана сверяться с постоянной
записью/операцией, а не выдавать новый геном или награду по одному AddReason.
Ошибка addEntity в этом пути логируется; готовой Genesis-транзакции/восстановления
он не создаёт. Обратный вызов смены роли не равен точному восстановлению удалённых
timers/AI state. Прямой `NPCEntity.setRole*` не является принятым takeover API.

Позднее 25.10 [живой прогон Fox → Fox](stage-0-v07-lifecycle-probe.md)
подтвердил этот queue/remove/add путь, прежний UUID, невалидность старого Ref
и равенство сериализованного снимка. Переход на другую роль/полный rollback
и настоящий unload/load чанка этим не приняты.
В том же последующем прогоне цикл chunk UNLOAD → LOAD проверен отдельно
после выхода/входа игрока, со стеками EntitySection/ChunkUnloadingSystem,
исчезновением/восстановлением lookup и сохранением UUID/сериализованного снимка.
Переход на другую роль/rollback/принятие Genesis остаются TODO.

`NPCEntity.isReserved/isReservedBy` — reservations, не доказательство owner или
primaryController другого мода. Найденный DeployableOwnerComponent относится
к deployables и не объявляется общим NPC ownership API. Контроллер неизвестного
NPC остаётся UNKNOWN; право Genesis на изменение из отсутствия маркера не следует.

## Native storage пока не прошёл требования Genesis

`IResourceStorage` предлагает отдельные `load/save/remove` с CompletableFuture.
В исследованном интерфейсе нет транзакции нескольких записей или CAS/revision.
Стандартный DiskResourceStorageProvider:

- при отсутствии codec возвращает новый resource при load и completed future
  без записи при save;
- в `lambda$load$0` ловит **IOException** из readAttributes как «файл не найден»
  и вызывает createResource; пустой файл тоже даёт createResource;
- после readSyncWithBak возвращает createResource, если результат null;
- save сериализует ресурс до вызова BsonUtil.writeDocument; весь этот метод
  нельзя считать неблокирующим только по типу CompletableFuture;
- BsonUtil.writeDocumentSync пишет временный JSON, закрывает BufferedWriter и
  вызывает FileUtil.atomicMove. Это не подтверждённая crash-safe транзакция
  Genesis, проверка durable flush/журнала/outbox ещё отсутствует.

**Вывод для 2A:** прямой адаптер над этим API пока NOT_QUALIFIED для
`hytale_native`/выбора native в auto. Нужны отдельные гарантии fail-closed,
идентичности store, CAS, atomic journal/outbox, crash recovery и durability.
Это оценка исследованного пути, не запрет будущего полноценного native provider.
При первой инициализации auto выбирает SQLite, если проверенного native нет,
как уже требует GDD. При сбое существующего store переключение на пустой store
по-прежнему запрещено. Провайдеры в коде ещё не реализованы, config не менялся.

## Read-only стенд и следующий прогон

[CompatibilityProbeCommand](../src/main/java/dev/ifelse/zirion/genesis/prototype/CompatibilityProbeCommand.java)
регистрируется только при `genesis.compatibilityProbe=true`. Через Wrapper:

```sh
./gradlew runServer -PgenesisCompatibilityProbe=true \
  -PhytaleAssets="/absolute/path/to/Assets.zip" --console=plain
```

`/genesiscompat` требует `genesis.prototype.compatibility`, наводку на NPC до
8 блоков и существующие UUID мира/существа. Выводит world/entity UUID,
результат UUID lookup, role ID, persistent model ID, reservation flag и класс
текущего ECS resource storage. Владение/контроллер/допуск явно UNVERIFIED.
Не создаёт WildCandidateRecord/геном, не копирует сущность, не меняет компоненты,
модель/AI и не вызывает save. Команда не является игровым анализом v0.7.
После первого клиентского прогона подготовлена запись результатов/отказов
в plugin logger; исходная запущенная сборка отправляла значения только игроку.
[Состояние развёртывания и полученные свидетельства](hytale-0.7.4.md#первый-клиентский-прогон-диагностики).

Далее нужны контролируемые live-сценарии на настоящих Wolf_Black/White и Fox:
двойной осмотр без изменений, сравнение world/entity UUID до/после stop/start,
реальная выгрузка, поздний наблюдатель, контролируемая смена роли с новым Ref,
snapshot/восстановление и отказ чужому controller. Нужны также авторитетные
права взаимодействий/analysis и боевые/инвентарные API до соответствующих этапов.
PlayerInteractEvent имеет targetRef и cancellation, но сам факт сигнатуры не
подтверждает используемый dispatch path/поток; в игровую логику он не подключён.

## Проверки

25.10.2026: `build verifyPluginJar testGddCatalogValidator verifyGddCatalogs`
BUILD SUCCESSFUL. **342 JUnit-теста выполнены**, 0 failures/errors/skipped;
34 Python-теста PASS, нормативные каталоги valid=true. Inventory воспроизведён
на локальных входах. Ядро не получило Hytale imports; entry point добавляет
только opt-in регистрацию команды. Сервер/клиент в этом пакете не запускались.
