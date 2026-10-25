# 0A: другая роль и собственный диагностический компонент

Дата: **25.10.2026**. **0A IN_PROGRESS, D13 OPEN**.
**Технический цикл Fox → Genesis_CompatibilityFox → Fox с сохранением marker
и равенством финального сериализованного снимка: PASS 25.10.2026.**
Предыдущие [restart](stage-0-v07-fox-live.md) и
[chunk UNLOAD/LOAD/перепривязка Fox → Fox](stage-0-v07-lifecycle-probe.md)
остаются отдельными свидетельствами.
Позднее в тот же день [общий сценарий](stage-0-v07-batch-probe.md) подтвердил
persist/reload установленного marker через restart и естественный UNLOAD/LOAD,
без повторного входа игрока. Общая проверка — 349 JUnit/34 Python PASS.
Следующий [admission probe](stage-0-v07-adoption-preflight.md) подтвердил, что
установка marker не предоставляет права принятия NPC; формат компонента сохранён.

## Границы эксперимента

Цель — та же командно созданная Frozen Fox:
world `634dbbc2-0c16-4a54-8239-01c153f7f299`,
entity `c116a72e-1b2c-399d-b1eb-3aa009de6366`.
Контроллер/владение/игровой допуск остаются UNVERIFIED; геном, кандидат,
питомец и первооткрытие не создаются. Свой marker не означает ownership.

Новая **техническая** Hytale-роль `Genesis_CompatibilityFox` — Variant с Reference
`Fox` и пустым Modify. Это другой зарегистрированный Role ID с унаследованными
параметрами/AI/моделью. Новые модели/текстуры/анимации не создавались.
Переход с другим поведением/rig и полноценное принятие Genesis этим не проверяются.
Это AI Role ID Hytale, не одна из семи игровых ролей Genesis этапа 2D.
Вызов `requestRoleChange(..., changeAppearance=false)` сохраняет существующую
модель, масштаб и attachments. Роль меняется через штатную очередь движка.

## Компонент и команды

[RoleProbeMarker](../src/main/java/dev/ifelse/zirion/genesis/prototype/compatibility/RoleProbeMarker.java)
зарегистрирован через plugin entity store registry под ID
`genesis:compatibility_probe_v1`. Diagnostic schema 1 не относится к geneSchema v1.
Codec регистрируется и с выключенными действиями для чтения оставшегося marker.
Ничего автоматически на NPC не добавляется.

Активный marker содержит operation UUID, world/entity UUID и точную строку
исходного сериализованного BSON JSON (не более 128 KiB). Clone/codec сохраняют
снимок; неизвестные компоненты в нём не выбрасываются.
[RoleProbeSnapshot](../src/main/java/dev/ifelse/zirion/genesis/prototype/compatibility/RoleProbeSnapshot.java)
сравнивает весь сериализованный документ, исключая только наш marker в текущем
снимке. Это **сравнение**, не механизм перезаписи чужих компонентов или отката AI.
Snapshot payload не отправляется игроку и не печатается в обычный status log.

Изменяющая команда включается отдельно от read-only `/genesiscompat`:

```sh
./gradlew runServer -PgenesisCompatibilityProbe=true \
  -PgenesisCompatibilityRoleProbe=true \
  -PgenesisCompatibilityWorldId=<fixture-world-uuid> \
  -PgenesisCompatibilityEntityId=<fixture-entity-uuid> \
  -PhytaleAssets="/absolute/path/to/Assets.zip" --console=plain
```

Без валидных явно заданных UUID команда не регистрируется. Право:
`genesis.prototype.compatibility.role`; допускается только ConsoleSender.INSTANCE,
callback выполняется через AbstractWorldCommand в потоке нужного мира.
В каждом вызове выполняется свежий UUID lookup. Нужны NPC/Dirty/Frozen,
существующий UUID, незарезервированная сущность; смена роли проверяет pending.
Начало требует Fox и согласованных persistent/runtime model ID Fox.
Эти проверки ограничивают dev-субъект; отсутствие reservation не доказывает owner.

| Команда в консоли | Действие / отказ |
| --- | --- |
| `genesisroleprobe status --world default` | Читает текущую роль/operation, ничего не добавляет |
| `genesisroleprobe begin --world default` | Снимает сериализованный baseline, добавляет только свой marker, markDirty |
| `genesisroleprobe enter --world default` | Ставит Fox → Genesis_CompatibilityFox в очередь |
| `genesisroleprobe return --world default` | Ставит Genesis_CompatibilityFox → Fox в очередь |
| `genesisroleprobe finish --world default` | В Fox сравнивает весь снимок; при равенстве удаляет свой marker и markDirty |
| `genesisroleprobe verify --world default` | Сравнивает baseline, разрешая только соответствующее значение NPC.RoleName |
| `genesisroleprobe admission --world default` | Читает только NPC-блокеры допуска; marker не даёт прав, ресурсы/контроллер не меняются |
| `genesisroleprobe load --world default --sectionX X --sectionY Y --sectionZ Z --then verify` | Загружает существующую 3D-секцию и выполняет проверку в том же callback мира; другие допустимые действия — в общем протоколе |

`queued` не означает commit: завершение подтверждать последующим status/dump и
runtime-наблюдением. При расхождении finish оставляет marker/снимок и сообщает
ключи изменившихся компонентов. Другие компоненты не перезаписываются.
При проблеме нельзя создавать замену NPC или объявлять полный rollback.
После restart собственная сериализованная диагностика остаётся читаемой,
но её crash recovery в этом пакете не принимается.

## Codec default: найденный и исправленный отказ

Первый запуск в 16:04 UTC остановился до загрузки мира: ComponentRegistry
вызывает BuilderCodec.validateDefaults при регистрации, а afterDecode требовал
полностью заполненный marker. Стек: RoleProbeMarker.validate →
BuilderCodec.validateDefaults → ComponentRegistry.registerComponent → plugin setup.

Во втором воспроизведении debugger перехватил bootstrap перед исключением:
`schema=0`, operation/world/entity/snapshot = null. Для этого кратко остановлен
только bootstrap thread; мир ещё не запущен. Изменений evaluator не выполнял.
После чтения значений воспроизведение продолжено до штатного отказа.

Исправлено: валидный default имеет schema 1 и явно **неактивен**; полностью пустой
marker не совпадает ни с каким fixture scope и не выдаёт baseline/operation.
Частичные данные, другая schema и неверный UUID снимка по-прежнему отклоняются.
Добавлена проверка настоящего `CODEC.validateDefaults` и неактивности default.

## Проверки и развёртывание

`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` после исправления
PASS: **348 JUnit**, **34 Python**, без failures/errors/skipped.
Шесть новых тестов: codec/clone/raw snapshot, регистрационный default,
отказ malformed/cross-entity/recursive/oversized data, сохранность текущего
документа при сравнении и выявление изменений/потери чужих opaque bytes.
Нормативные JSON/GDD не менялись.

Перед экспериментом после штатного stop создан backup мира/config/permissions
`run/backups/pre-role-probe-2026-10-25/`, без credentials.
Исправленный запуск: `run/logs/2026-10-25_19-09-02_server.log`.
В **16:09:05 UTC** команда включена для явной пары UUID; в **16:09:11**
NPC validation завершена, загружены 3 конфигурации (1 Variant, 2 Generic),
Genesis/определения включены; в **16:09:12** Booted.
Help и первый status проверены: пока `fixture_not_loaded` до входа игрока.

## Живой результат

Журнал времени — UTC, тот же исправленный запуск. Debugger использовал два
несуспендирующих logpoints RoleChangeSystem и один в отчёте команды; после
исправления codec ошибок logpoints нет. Значения evaluator только читались.

| Время UTC | Свидетельство |
| --- | --- |
| 16:13:30 | Fixture загружен: role=Fox, marker отсутствует, Ref index=29 |
| 16:14:12 | Исходный серверный dump: marker отсутствует |
| 16:14:16 | begin: добавлен marker с operation `1eb262fc-6ca7-45fa-818e-077d21e8cad1` |
| 16:14:51 | Dump подтвердил Schema/Operation/World/Entity/Snapshot в собственном компоненте |
| 16:14:55.603–.633 | Debugger: Fox → Genesis_CompatibilityFox (runtime index 1210), changeAppearance=false, тот же UUID; oldRefValid=false, новый Ref index=264 |
| 16:15:18 | status/dump: новая роль активна; весь marker совпал с исходным, в остальном сериализованном снимке изменён только NPC.RoleName |
| 16:15:22.102–.136 | Debugger: возврат в Fox (runtime index 978), тот же UUID; oldRefValid=false, новый Ref снова использовал index=264 |
| 16:15:58 | status/dump: Fox; все сериализованные данные вместе с marker совпали со снимком после begin |
| 16:16:02 | finish: snapshot equal, собственный marker удалён |
| 16:16:45 | Финальный независимый серверный dump полностью совпал с исходным до begin; marker отсутствует |
| 16:16:48 | world save завершён после markDirty при очистке |

Все отчёты команды и наблюдения перехода — в `WorldThread - default`,
storeThread=true. World/entity UUID исходные, operation один и тот же.
Модель/масштаб/позиция и все сериализованные компоненты вернулись к baseline.
Смена роли не создала другое животное. Индекс Ref переиспользован даже при
oldRefValid=false: сравнение строк/index не является проверкой идентичности Ref.

Snapshot внутри marker — **2316 UTF-8 bytes**;
SHA-256 `b1930264df46fe17cc394f9f7e0618ffacfbc18da2f57732cdc6c0f21c7e4f94`.
Равенство проверено по полному marker и точной строке Snapshot в серверных
снимках обеих ролей. Java String hash в debugger совпадал (`1943316415`), но
вывод не основан только на нём. Это диагностический BSON JSON, не GEN1/JCS
и не доказательство provenance.

Три agent-logpoints и временная attach configuration удалены; активных
debug sessions не осталось. Две пользовательские exception-breakpoints
сохранились выключенными. Сервер оставлен запущенным.

## Что не закрыто

- Новый rig/другая модель и другое поведение AI; alias наследует Fox без Modify.
- Полный snapshot/rollback несериализованного AI, таймеров, messaging; аварийный
  отказ add и восстановление после crash. Команда сравнивает данные, не лечит их
  перезаписью чужих компонентов.
- Persist/reload собственного marker первоначальным циклом не проверялся;
  позднее закрыт [общим сценарием](stage-0-v07-batch-probe.md) 25.10.2026.
- Владение/primaryController, допуск живого NPC, конфликт с другим модом,
  игровые приручение/анализ/сохранения Genesis, остальные виды.

0A/D13 остаются открыты. Прохождение одной технической Variant-роли
не даёт права включать вид или объявлять безопасным произвольный takeover.
