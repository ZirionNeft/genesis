# 0A: общий прогон технического стенда Fox

Дата: **25.10.2026**, Hytale **0.7.4**. **PASS в описанных границах;
0A IN_PROGRESS, D13 OPEN.**

Сборку и проверки чистого ядра/каталогов теперь можно запускать одной командой:

```sh
./gradlew verifyDevelopment --console=plain
```

Задача объединяет `build`, `verifyPluginJar`, `testGddCatalogValidator` и
`verifyGddCatalogs`. Нужны JDK 25 и Python 3.10+; `pythonExecutable` поддерживается
как раньше. Сервер/клиент эта задача не запускает. Полный прогон после изменения:
**349 JUnit, 34 Python, 0 failures/errors/skipped**, упаковка/каталоги PASS.
Один новый JUnit проверяет сравнение промежуточной роли: разрешено только точное
значение NPC.RoleName, другие поля/чужие компоненты не игнорируются; исходные
документы и baseline при сравнении не меняются.

## Что объединено в живом сценарии

Один субъект и один operation проходят всю цепочку: baseline → marker → другая
роль → сохранение/stop/start → загрузка секции → естественный UNLOAD/повторный
LOAD → возврат в Fox → сравнение/удаление marker → сохранение.

Субъект прежний: командно созданная Frozen Fox, world
`634dbbc2-0c16-4a54-8239-01c153f7f299`, entity
`c116a72e-1b2c-399d-b1eb-3aa009de6366`. Operation этого прогона:
`e7f64ec9-9643-46b2-8047-96af8d045726`. Координаты существующей 3D-секции:
`-27,3,8` (позиция NPC `-848.5,121,283.5`, размер секции 32).
Копия NPC/геном/приручение/ownership не создаются. Техническая Variant
`Genesis_CompatibilityFox` наследует Fox без Modify, модель/rig прежние.

В [консольный стенд](stage-0-v07-role-marker-probe.md) добавлены:

- `verify`: сравнение с marker baseline в Fox или Genesis_CompatibilityFox;
  допускается ровно соответствующее значение NPC.RoleName. При любом другом
  расхождении marker остаётся, выводится отказ и список ключей.
- `load --sectionX X --sectionY Y --sectionZ Z [--then ACTION]`: нативная загрузка
  существующей секции с `NO_GENERATE | SET_TICKING`; без генерации ландшафта,
  принудительной выгрузки или `chunk forcetick`. По умолчанию ACTION — status;
  также разрешены begin/enter/return/verify/finish.

Загрузка асинхронная, лимит ожидания 15 секунд на копии future; tick не ждёт
диск/future. Таймаут не отменяет нативную загрузку, но запрещает последующее
действие. Завершение/действие выполняются через executor мира, с новым UUID lookup
и прежними world/thread/Frozen/NPC/reservation/scope guards. Успех загрузки
секции сам по себе не означает успех проверки сущности.

Без игрока секция может естественно выгрузиться примерно через семь секунд;
повторный запрос уже активной секции не гарантирует новый срок. Поэтому действие
`--then` выполняется **в том же callback мира**, что и завершение загрузки.
Это устраняет промежуток между двумя консольными командами. `queued` по-прежнему
не подтверждает завершённую смену роли: следующий verify — отдельная точка.
Opt-in/точные UUID и ConsoleSender.INSTANCE обязательны, как в исходном стенде.

## Протокол повторения

Запустить сервер с `genesisCompatibilityRoleProbe=true` и явно заданными UUID
fixture (флаги в исходном документе). Значения секции ниже относятся только к
описанной Fox; для нового субъекта сначала проверить его позицию/идентичность.
Каждый шаг выполнять после успешного результата предыдущего, не вставлять всю
последовательность команд без ожидания.

| Шаг | Команда / свидетельство |
| --- | --- |
| Исходное состояние | `genesisroleprobe load --world default --sectionX -27 --sectionY 3 --sectionZ 8`; Fox, unarmed; отдельный native entity dump |
| Marker | Та же load-команда с `--then begin`; записать operation и отдельный dump |
| Переход | `--then enter`, затем `--then verify`; новая роль, прежний operation, весь marker совпадает |
| Диск | `world save --world default --confirm`, дождаться завершения; штатный `stop`, повторный runServer с теми же настройками |
| После запуска | `load … --then verify`; прежний UUID/operation, другая роль и снимок; вход игрока не требуется |
| Выгрузка | Без игрока дождаться естественного UNLOAD; status без load даёт fixture_not_loaded; logpoint подтверждает EntitySection/SectionUnloadingSystem |
| Повторный LOAD | `load … --then verify`; native dump равен снимку другой роли до restart, marker побайтово сохранён |
| Возврат | `load … --then return`, затем `load … --then verify`; Fox, прежний operation; отдельный dump равен armed baseline |
| Очистка | `load … --then finish`; snapshot equal, свой marker удалён; отдельный native dump равен исходному до begin |
| Сохранение | `world save --world default --confirm`, дождаться завершения |

`load …` означает ту же полную команду с world/sectionX/sectionY/sectionZ.
Native dump: `entity dump --entity <fixture-uuid> --world default`.
Из-за естественной выгрузки отдельный dump может потребовать новой загрузки.
Учитывать только dump после последнего соответствующего CommandManager-запроса;
`not found` не позволяет брать предыдущий снимок из журнала.

## Свидетельства общего прогона

Время UTC. Первая часть выполнена работающим исходным стендом, после restart —
новой версией; дополнительный штатный restart установил вариант с `--then`.
Marker format/ID не менялся; одна операция продолжилась через оба restart.

| Время | Результат |
| --- | --- |
| 16:34:01–23 | baseline/dump/begin/armed; UUID прежний, operation выше, отличие только собственный marker |
| 16:34:25.985–26.013 | Debugger: RoleChangeSystem UNLOAD → LOAD того же UUID в WorldThread; status/dump — Genesis_CompatibilityFox, изменён только NPC.RoleName |
| 16:34:46–54 | Мир сохранён, штатный stop завершён |
| 16:35:39 | Genesis стартовал после stagePlugin; определения/pack загружены |
| 16:36:12–19 | До загрузки fixture_not_loaded; load активировал EntitySection; verify PASS в новой роли с прежним operation, storeThread=true |
| 16:36:22.341 | Debugger: естественный UNLOAD через SectionUnloadingSystem → EntitySection → UUIDSystem, WorldThread |
| 16:36:28–34 | Активная цель не найдена, who=0; load без координат отклонён |
| 16:36:57–16:37:03 | Debugger подтвердил обратный LOAD через ChunkGrid/EntitySection; native dump полностью равен entered, точный marker сохранён; verify PASS |
| 16:37:19–21 | RoleChangeSystem вернул Fox, verify PASS; UUID/operation прежние |
| 16:38:00 | Native returned dump полностью равен armed; естественные выгрузки не теряют marker |
| 16:39:37–16:40:26 | Штатный stop/start для версии с объединённым callback |
| 16:41:08–11 | `load --then verify` PASS в Fox; `load --then finish` сравнил весь baseline и удалил только свой marker |
| 16:41:19 | Мир сохранён после очистки |
| 16:41:59–16:42:03 | Новый LOAD/status — Fox/unarmed; независимый native final dump полностью равен исходному baseline |

Во всех точках тот же world/entity UUID. Ref/его индекс не используются как
постоянная идентичность; индекс может переиспользоваться. Все наблюдённые
LOAD/UNLOAD и действия выполнялись в WorldThread - default, storeThread=true.
Вход клиента после первого stop не использовался. Несуспендирующие logpoints
подтвердили разные причины lifecycle; ошибок debugger не было. Два agent-logpoints
и временная attach configuration удалены, debugger отключён; две пользовательские
exception-breakpoints сохранились выключенными. Сервер оставлен работающим.

Шесть native dump сохранены локально в игнорируемом `run/compatibility-batch/`:
baseline/armed/entered/reloaded/returned/final и report.json. Сравнение независимо
подтвердило entered=reloaded, armed=returned, baseline=final, неизменность всего
marker и единственное отличие RoleName в промежуточных снимках.
Snapshot marker: 2316 UTF-8 bytes,
SHA-256 `b1930264df46fe17cc394f9f7e0618ffacfbc18da2f57732cdc6c0f21c7e4f94`.
Сравнение основано на полном содержимом, не только checksum. Локальные логи,
дампы, мир и учётные данные в JAR/Git не включаются.

## Оставшаяся матрица 0A

| Пакет | Статус и зависимость |
| --- | --- |
| UUID/роль/свой marker/штатный restart/EntitySection UNLOAD-LOAD/очистка | PASS для одной Frozen Fox в этом прогоне |
| Волк и остальные исходные виды | Позднее 25.10 серверный цикл обоих wolf fixture PASS: [матрица и свидетельства](stage-0-v07-species-probes.md); клиент/возраст и прочие десять видов TODO |
| Другое поведение AI/другой rig и rollback при отказе add | TODO: другой подготовленный binding, snapshot несериализуемого состояния и управляемый отказ |
| Принятие/освобождение того же NPC, чужой controller/права | TODO: явный контракт авторизации и контроллеров; marker ownership не доказывает |
| Store/CAS/outbox/crash recovery | TODO этапа 2A; штатный restart ECS не заменяет эти гарантии |
| HP/урон/скорость/команды/езда/груз/свои и чужие modifiers | TODO проверок 0A перед 2D |
| UI/игровая авторизация/анализ и клиентская приёмка | TODO соответствующих действий/ассетов; сборка не заменяет игру |

Эти пакеты можно собирать в следующие общие сеансы после подготовки зависимостей.
Текущий результат не является приёмкой всей 0A или игры v0.7.
