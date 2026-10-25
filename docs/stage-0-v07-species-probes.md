# 0A: матрица видов и серверный прогон двух вариантов волка

**25.10.2026, Hytale 0.7.4. 0A IN_PROGRESS, D13 OPEN.**
Серверные технические циклы Wolf_Black и Wolf_White — **PASS**.
Полная проверка `verifyDevelopment` — **353 JUnit/34 Python PASS**, без
failures/errors/skipped. Клиент для нового прогона не запускался.

## Матрица 12 исходных видов

Основа — [инвентарь точного артефакта 0.7.4](stage-0-v07-vanilla-inventory-0.7.4.json)
и живые свидетельства ниже/[Fox](stage-0-v07-batch-probe.md).
Статический инвентарь содержит пути, хеши, прямые модели/клипы и их ограничения;
его исторические поля UNVERIFIED не переписываются по результатам другого теста.
Обозначение модели в таблице — ID, а не доказательство совместимости rig/клипов.

| Вид GDD | Кандидаты Role ID | Model ID в инвентаре | Серверный технический цикл | Допуск к приручению |
| --- | --- | --- | --- | --- |
| genesis:wolf | Wolf_Black, Wolf_White | Wolf_Black, Wolf_White | PASS 25.10, оба Frozen fixture | UNVERIFIED |
| genesis:fox | Fox | Fox | PASS 25.10, Frozen fixture | UNVERIFIED |
| genesis:hyena | Hyena | Hyena | TODO | UNVERIFIED |
| genesis:horse | Horse | Horse | TODO | UNVERIFIED |
| genesis:deer | Deer_Doe, Deer_Stag | Deer_Doe, Model_Deer_Stag | TODO | UNVERIFIED |
| genesis:antelope | Antelope | Antelope | TODO | UNVERIFIED |
| genesis:bison | Bison | Bison | TODO | UNVERIFIED |
| genesis:boar | Boar | Boar | TODO | UNVERIFIED |
| genesis:mosshorn | Mosshorn, Mosshorn_Plain | Mosshorn, Mosshorn_Plain | TODO | UNVERIFIED |
| genesis:raven | Raven | Raven | TODO | UNVERIFIED |
| genesis:owl | Owl_Brown, Owl_Snow | Owl_Brown, Owl_Snow | TODO | UNVERIFIED |
| genesis:hawk | Hawk | Hawk | TODO | UNVERIFIED |

Для **каждой** строки возрастные сигналы ванильного NPC ещё не подтверждены,
начальный возраст Genesis — UNDECIDED; owner/primaryController/provenance и
безопасное принятие — UNVERIFIED. Поэтому ни один вид этой матрицей не включён
в игровой taming. Наличие Role ID не доказывает свободного controller.
Отсутствие клиентского rig/clip-прогона и авторизации — причины недопуска
даже для Wolf/Fox. Для остальных десяти видов дополнительно нет живого
серверного прогона. Взрослый возраст нельзя предполагать по выбранной модели.

## Изменения стенда

[RoleProbeProfile](../src/main/java/dev/ifelse/zirion/genesis/prototype/compatibility/RoleProbeProfile.java)
задаёт закрытые пары source/alias/model и имена команд. Alias каждого волка —
Variant своего исходного Role ID с пустым Modify. Переход остаётся с
changeAppearance=false; смена rig, модели или поведения этим не проверяется.

| Профиль | Команда | Дополнительное Gradle property для UUID |
| --- | --- | --- |
| fox | genesisroleprobe | genesisCompatibilityEntityId (прежнее имя) |
| wolf_black | genesiswolfblackprobe | genesisCompatibilityWolfBlackEntityId |
| wolf_white | genesiswolfwhiteprobe | genesisCompatibilityWolfWhiteEntityId |

Общие флаги — `genesisCompatibilityRoleProbe=true`, точный
`genesisCompatibilityWorldId`; read-only probe включается отдельно как раньше.
UUID опциональны по профилям; без UUID профиль не получает изменяющую команду.
Все заданные UUID проверяются до регистрации: canonical UUID, разные между
профилями. Ошибка одного не оставляет частично включённый набор команд.

Формат/ID [RoleProbeMarker](../src/main/java/dev/ifelse/zirion/genesis/prototype/compatibility/RoleProbeMarker.java)
**не менялся**. Профиль определяется по исходному RoleName в сохранённом baseline.
Проверка допускает только соответствующую пару source/alias и сверяет профиль
marker с командой. Snapshot лисицы нельзя использовать для перехода волка;
другие поля NPC и чужие компоненты по-прежнему сравниваются целиком.

Добавлены четыре JUnit: сохранность codec/snapshot для всех профилей и отказ
чужим ролям; неизвестные/alias baseline; атомарная проверка конфигурации и
запрет повторного UUID; точные Reference/Type/пустой Modify упакованных alias.

## Подготовка новых fixture без клиента

Отдельный флаг `-PgenesisCompatibilityCreateFixtures=true` регистрирует только
консольную `genesisrolefixture <wolf_black|wolf_white> <x> <y> <z> --world <name>`.
По умолчанию флаг false. Право — `genesis.prototype.compatibility.fixture.create`.
Мир должен совпасть с явно заданным world UUID; координаты конечные, секция
существует, генерация запрещена. Загрузка асинхронная с timeout 15 секунд,
продолжение — в потоке мира, без join/future wait на tick.

Вызов нативного NPCPlugin.spawnEntity создаёт **новый** ванильный тестовый NPC
со свежим UUID и случайным нативным масштабом ModelAsset. Frozen добавляется
в Holder **до** SPAWN. Чужие/существующие животные не принимаются и не копируются;
геном/кандидат/питомец/прогресс исследования не создаются. Это администраторская
подготовка стенда, не игровое рождение или crash-safe транзакция Genesis.

Применяется один attempt на профиль за процесс, резервируется до async-загрузки;
настроенный профиль/Fox/повторный вызов отклоняются, включая повтор после ошибки.
Автоспавна/автоповтора нет. После подготовки записать выданные UUID, сохранить
мир и перезапустить **без флага создания**, с UUID в свойствах профилей.
Повторная ручная подготовка в новом процессе не является механизмом восстановления.

В этом прогоне созданы два новых Frozen wolf fixture рядом с прежней лисицей,
в существующей секции `-27,3,8`. Прежняя Fox не заменялась. Backup мира:
локальный игнорируемый `run/backups/0a-wolves-20261025T191151Z/default`.

| Поле | Wolf_Black | Wolf_White |
| --- | --- | --- |
| Entity UUID | 054be9c5-064b-3fa5-8f7a-311e56039738 | 873db1a9-8427-318f-b38c-341c0d0ea43c |
| Operation | 09d873a1-a6ef-482c-acf5-e115cfc5fe44 | b795aef0-00fc-4f7a-b146-4fe436b63198 |
| Позиция | -844.5, 121, 281.5 | -842.5, 121, 281.5 |
| Model ID | Wolf_Black | Wolf_White |
| Сохранённый масштаб | 0.8814154267311096 | 0.870799720287323 |
| Snapshot UTF-8 bytes | 2250 | 2248 |
| Snapshot SHA-256 | bed52949b0a1c3b61ca466f1c7b2ffc7c0e3ca9d9c8e6164a3098980557df9e2 | ad5c4a489c8ba70955a3914b969efa594beb9a5eb8bea68dc394a66004b1a40f |

Общий world UUID: `634dbbc2-0c16-4a54-8239-01c153f7f299`.
Это известные тестовые субъекты, не runtime eligibility естественных животных.
У Wolf_White исходный ModelAsset имеет Parent Wolf_Black; runtime разрешил
его собственный ID. Клиентские текстуры/анимации/геометрия не принимались.
В сохранённых stats обоих wolf — Health=100 и NPC_Max additive=3; проверена
сохранность этих данных, не применение статов GDD или полнота боевой реализации.

## Живые свидетельства

Время UTC. После подготовки создание выключено, команды профилей связаны
с точными UUID. Далее выполнен [общий протокол](stage-0-v07-batch-probe.md)
отдельно для каждого волка, с общим save/restart:

| Время | Свидетельство |
| --- | --- |
| 19:13:16.890 / 19:13:19.296 | Debugger: нативный SPAWN каждого UUID, source RoleName, Frozen=true уже в UUIDSystem.onEntityAdded; WorldThread |
| 19:13:23 | Повтор подготовки wolf_black отклонён: configured_or_already_attempted |
| 19:13:26–19:14:40 | Save/штатный stop/start; создание выключено, обе команды зарегистрированы по UUID |
| 19:15:36–51 | LOAD обоих с прежними UUID; baseline native dump обоих волков и Fox; попытка вызвать выключенную команду подготовки — command not found |
| 19:16:17 / 19:16:34 | Begin обоих operation выше; собственный marker, без изменения генетики/прав |
| 19:16:20.372–.401 / 19:16:36.845–.859 | Debugger: RoleChangeSystem UNLOAD → LOAD тех же UUID в Genesis_CompatibilityWolfBlack/White |
| 19:16:23–49 | verify/dump: изменены только NPC.RoleName и собственный marker; полные остальные данные равны baseline |
| 19:16:53.024 / .035 | Естественный UNLOAD обоих с marker через SectionUnloadingSystem/EntitySection, без принудительной выгрузки |
| 19:16:50–19:17:51 | Мир сохранён, штатный stop/start с установленными marker; обе alias-роли читаются |
| 19:18:42.962 / .972 | Debugger: EntitySection LOAD обоих UUID в alias-ролях, Frozen=true |
| 19:18:42–58 | verify/native dump обоих: entered=reloaded целиком, operation и точная строка marker Snapshot совпали; who=0 |
| 19:19:12.862 / 19:19:32.715 | Возврат через RoleChangeSystem: source RoleName, тот же UUID, oldRefValid=false, changeAppearance=false, WorldThread |
| 19:19:15–18 / 19:19:35–38 | verify PASS, finish сравнил полный baseline и удалил только свой marker у каждого волка |
| 19:19:22–55 | Независимые final native dump равны baseline, marker отсутствует; мир сохранён |
| После очистки | Fox final dump равен её baseline этого прогона; геном/кандидат ей не добавлялись |

Локальные дампы и независимый report — игнорируемый `run/compatibility-wolves/`:
baseline/entered/reloaded/final каждого волка и baseline/final лисицы.
Проверка связывает dump с последней соответствующей командой, не принимает
старый dump после not found; сравнивает полные документы, не только хеши.
При естественной выгрузке повторялись загрузка/чтение, геномы не перебрасывались.
Snapshot JSON — диагностический BSON JSON, не GEN1/JCS и не provenance.

Три agent-logpoints/временная attach configuration удалены; активных sessions
нет, две пользовательские exception-breakpoints остались выключенными.
Сервер оставлен работающим с созданием fixture **выключенным**, все три NPC —
в исходных ванильных ролях, без диагностических marker, по-прежнему Frozen.

## Следующая работа

Для первого набора Wolf/Fox требуются контролируемое принятие/освобождение,
контракт primaryController/provenance/прав и отказ чужому владельцу, другой
AI/rig binding и полный snapshot/rollback при отказе add. Runtime AI/timers/
messaging не восстанавливаются простым сравнением сериализованного Holder.
Клиентская rig/clip-проверка и решение начального возраста остаются открыты.
Остальные десять видов проверяются до их игрового включения. Store/CAS/outbox
и crash recovery требуют этапа 2A; одинаковые данные после normal restart
не доказывают эти гарантии. 0A/D13 не закрыты.
