# 0A: допуск дикого NPC и повторная проверка прав

**25.10.2026, Hytale 0.7.4. Чистые правила допуска и серверная диагностика
отказов PASS; 0A IN_PROGRESS, D13 OPEN.**
Пакет не создаёт питомца/controller/store-транзакцию. Контролируемое принятие,
освобождение и настоящий конфликт со сторонним модом ещё не приняты.

## Источники и граница доверия

Основание — GDD v0.7 §10 и ограничения AGENTS/ADR-001: чужие NPC сохраняются,
автоматический takeover/explicit transfer запрещены, конфликт даёт отказ.
Нормативный GDD/JSON не менялись, численные defaults не добавлялись.

Повторно прочитаны список классов и сигнатуры NPCEntity установленного JAR 0.7.4,
SHA-256 `23fad4c8d42b73b4139540bbae26ca0b8a43388f1e07e7b423d616e81834fa79`.
Role/motion controller/reservation API не дают общего подтверждённого
owner/primaryController/provenance API животных; DeployableOwnerComponent —
другая подсистема. Это ограниченный аудит, не доказательство отсутствия любого
возможного API во всём сервере.

Знакомые Role ID/модель, Frozen, отсутствие reservation/marker, UUID, checksum
или GEN1 не доказывают свободного controller и дикого происхождения.
`RoleProbeMarker` остаётся диагностикой; его формат/codec/ID не менялись.

## Правила чистого ядра

[AdoptionEvidence](../src/main/java/dev/ifelse/zirion/genesis/core/control/AdoptionEvidence.java)
и [WildAdoptionPolicy](../src/main/java/dev/ifelse/zirion/genesis/core/control/WildAdoptionPolicy.java)
не импортируют Hytale, не делают IO/RNG и не меняют NPC/геном/ресурсы.
Это внутренние доверенные серверные входы, не клиентский DTO или стабильный
Public API. Само имя источника/переданный флаг не доказывают его подлинность.

| Вход | Условие допуска / отказ |
| --- | --- |
| Request | operation + реальный actor + provider/world/entity + intent; только ADOPT, OBSERVE/AUTOMATIC отклоняются |
| Identity | Существующий WildCandidateRecord.NpcIdentity, не Ref/index/network ID |
| Origin | Только доверенный VANILLA_WILD; UNKNOWN/CONFLICT и Genesis/external/admin/import — отказ этому пути |
| Owner | Только положительно подтверждённый UNOWNED; UNKNOWN/CONFLICT/существующий owner запрещают новое принятие, даже тому же игроку |
| Controllers | Нужны непустые доверенные claims и полное покрытие; отсутствие компонента не означает UNCLAIMED |
| Конфликты | UNKNOWN/неполное покрытие, повтор source ID или разные primaryController ID запрещают допуск; нет выбора «последнего» мода |
| Заявленный controller | Внешний — отказ; Genesis — уже принят, новое wild adoption тоже запрещено |
| Диагностическая цель | Исключается отдельно, без присвоения ложной provenance |
| Допуски | Доступность, reservation clear, binding, безопасное восстановление поведения, политика начального возраста, durable commit — только PASS |
| Authorization | Привязана ко всему Request; права и Before* только PASS, отказ/ошибка/таймаут запрещают следующий шаг |

Логический `genesis:primary_controller` обозначает будущий controller Genesis;
это проектный ID, не найденное поле Hytale, не Role ID и не diagnostic marker.
Идентификатор сам по себе не предоставляет полномочий. Claims сохраняются
immutable и сортируются по source; перестановка одинаковых наблюдений допустима.

`inspect(facts)` возвращает только NPC-блокеры. Даже пустой набор не проверяет
игрока и не разрешает принятие. `begin(request, facts, authorization)` даёт Denied
с immutable причинами или Ready с удержанным baseline. Ready создаётся policy,
но **не является** токеном прав, commit, controller claim или записью питомца.

`revalidate(ready, freshFacts, freshAuthorization)` повторяет проверки и сравнивает
полные факты/авторизацию. Изменение owner/controller/identity/допусков/прав/Before*
или их ревизии запрещает продолжение. StateVersion — epoch доверенного источника
и монотонная **ревизия состояния**, не число осмотров/timestamp/Ref index.
Она обязана меняться при релевантных изменениях, в том числе control/owner,
binding/политик/конфига и перезапуске источника. Authorization имеет свою версию.
Отсутствие версии запрещает Ready. Так обнаруживается ABA: видимые данные снова
прежние, но ревизия уже другая; epoch запрещает перенос baseline через restart.

Настоящие сборщики этих фактов/ревизий ещё не реализованы. Before* здесь **не
вызываются**: coordinator должен собрать их результаты, включая config/taming.enabled
и актуальные игровые условия. Формулы доверия/тревоги и приручение не заменены.
Свежесть входов — ответственность сервера; два вызова чистой функции не создают
атомарности ECS/store. Перечитывание до commit/активации, защита от изменения
после revalidate, CAS/idempotency/outbox и recovery требуют 0A/2A.

## Read-only серверный адаптер

[NpcAdmissionProbe](../src/main/java/dev/ifelse/zirion/genesis/prototype/compatibility/NpcAdmissionProbe.java)
читает существующие UUID/NPC, активный UUID lookup и reservation в потоке мира.
`genesis:hytale_entity_store` — логическое пространство идентичности NPC,
не квалификация native storage для auto. Controller/owner/origin/revision,
gameplay bindings/restoration/initial age/store остаются UNKNOWN. Marker не
читается как источник прав; configured fixture дополнительно diagnosticTarget.
Результат — отказ до появления настоящих доверенных источников.

Scoped console команды используют прежние opt-in/точные UUID из
[стенда Wolf/Fox](stage-0-v07-species-probes.md), без выдуманного actor:

```text
genesisroleprobe load --world default --sectionX -27 --sectionY 3 --sectionZ 8 --then admission
genesiswolfblackprobe load --world default --sectionX -27 --sectionY 3 --sectionZ 8 --then admission
genesiswolfwhiteprobe load --world default --sectionX -27 --sectionY 3 --sectionZ 8 --then admission
```

Самостоятельное `admission --world default` требует уже загруженной цели.
Вызывается NPC-only inspect, не begin/права игрока/commit. Компоненты/controller/
геном/кандидат/предметы не меняются, загрузка секции остаётся асинхронной,
tick не ждёт диск/future. Read-only `/genesiscompat` также выводит локализованные
NPC-блокеры; dev permission и осмотр не считаются разрешением/согласием ADOPT.
Новый клиентский вывод в этом пакете не проверялся.

## Проверки и живые свидетельства

`./gradlew verifyDevelopment --console=plain`: **366 JUnit / 34 Python PASS**,
упаковка/каталоги valid, 0 failures/errors/skipped. 13 новых тестов охватывают
положительный доверенный сценарий/revalidate, observe/automatic, все остальные
origin, владельцев (включая того же игрока), внешнего/Genesis controller,
пустые/UNKNOWN/неполные/конфликтующие claims, каждый UNKNOWN/BLOCKED допуск,
отсутствие ревизий, подмену actor/operation/provider/world/entity/intent,
отказ прав/Before*, owner change/ABA/epoch, отзыв прав/ABA авторизации,
immutable данные и некорректные состояния claim/owner.
Внешний controller и права проверены **чистыми тестовыми входами**, не настоящим
сторонним модом/игровой авторизацией.

Перед обновлением сохранены native snapshots трёх fixture, мир сохранён;
сервер штатно перезапущен с теми же UUID и выключенным созданием fixture.
Время ниже UTC, world `634dbbc2-0c16-4a54-8239-01c153f7f299`:

| Время | Результат |
| --- | --- |
| 19:41:25–42 | Native before snapshots обоих wolf/Fox, save |
| 19:42:06–19 | Штатный stop/start, новый JAR/определения v0.7 доступны |
| 19:43:14 / 17 / 20 | Fox/Wolf_Black/Wolf_White: denied_npc_preflight, storeThread=true, WorldThread - default |
| 19:43:23 | Только на Fox добавлен diagnostic marker, operation a06d9687-b969-42ea-aa37-1d32bfc32310 |
| 19:43:26 | С marker — тот же полный набор блокеров, без повышения прав |
| 19:43:29–32 | Finish сравнил baseline и удалил marker; save |
| После очистки | Независимые after snapshots всех трёх полностью равны before |

Все четыре вызова дали NPC_REVISION_UNVERIFIED, ORIGIN_UNKNOWN, OWNER_UNKNOWN,
CONTROLLER_UNKNOWN, DIAGNOSTIC_TARGET, BINDING_UNVERIFIED, RESTORATION_UNVERIFIED,
INITIAL_AGE_UNRESOLVED, DURABLE_COMMIT_UNVERIFIED. Reservation/lookup не превратили
UNKNOWN в разрешение. Дампы/report локально в игнорируемом
`run/compatibility-admission/`; сравнены полные документы, не только хеши,
старый dump после not found не засчитывается. Роли/Frozen/UUID и остальные
сериализованные данные сохранены, diagnostic marker не осталось.

Debugger не подключался: проверялись новое чистое правило и наблюдаемые
read-only отказы, runtime-сбой не диагностировался. Сервер работает без JDWP,
клиент не запускался, приручение не включено.

Далее — доверенный runtime-источник provenance/control/owner и ревизий для
естественных Wolf/Fox, настоящие права явного действия/Before*, snapshot
несериализуемого AI/rollback при отказе add, контролируемое принятие/освобождение
и store 2A. Положительный игровой допуск нельзя включать снятием UNKNOWN вручную.
