# 0A: осмотр одной тестовой лисицы на 0.7.4

Дата: **25.10.2026**. **0A IN_PROGRESS, D13 OPEN**.
Среда и артефакты — [переход на 0.7.4](hytale-0.7.4.md).
Проверяется идентичность одного NPC, не готовность вида к приручению.

## Исходное состояние

Пользователь прислал скриншот двух результатов `/genesiscompat`.
В чате видна строка `Spawned NPC: Fox`; в серверном журнале до осмотров есть
два вызова `npc spawn`. Это технический NPC, созданный командой, а не
свидетельство естественного появления или игрового происхождения.
Такой субъект не даёт первооткрытия/награды и не становится питомцем Genesis.

| Поле | Оба клиентских осмотра |
| --- | --- |
| World UUID | `634dbbc2-0c16-4a54-8239-01c153f7f299` |
| Entity UUID | `c116a72e-1b2c-399d-b1eb-3aa009de6366` |
| UUID lookup matches | `true` |
| Role / persistent model | `Fox` / `Fox` |
| Reserved | `false` |
| ECS resource storage | `com.hypixel.hytale.server.core.universe.world.storage.resources.DiskResourceStorageProvider$DiskResourceStorage` |
| Controller / ownership / Genesis eligibility | `UNVERIFIED` |

Вызовы зафиксированы **15:13:54** и **15:14:21 UTC** в
`run/logs/2026-10-25_18-08-33_server.log`. Скриншот подтверждает успешный
вывод команды игроку; explicit `storeThread` в старом выводе отсутствует.
Callback прошёл проверку `store.isInThread()`, иначе эти строки не отправляются.
После restart новый plugin log подтвердил thread name и storeThread явно.

**Двойной read-only осмотр одной Fox: PASS в указанных границах.**
Результаты не доказывают отсутствие внешнего владельца: reservation не owner.
Геном, WildCandidateRecord и запись Genesis не создавались.

## Серверный снимок до stop/start

В **15:21:20 UTC** выполнена штатная read-only команда:

```text
entity dump --entity c116a72e-1b2c-399d-b1eb-3aa009de6366 --world default
```

Сущность найдена по UUID: `Ref{store=...Store@759586490, index=152}`.
Сериализованный Holder содержит прежний binary UUID в компонентах UUID и NPC,
`NPC.RoleName=Fox`, `Model.Model.Id=Fox`, scale `1.0020575523376465`.
Позиция `(-848.5, 121.0, 283.5)`. WorldConfig binary UUID декодирован и совпал
с клиентским world UUID. Снимок и чтение config не изменяют NPC/геном.

В снимке есть компонент **Frozen**; кем он установлен, здесь не определено.
Поэтому этот прогон не принимает обычное движение/AI. Поле NPC SpawnInstant
не объявляется возрастом Genesis. Дамп использует `Store.copyEntity` и
сериализацию Holder; поля клона вроде `role=null` в его toString не являются
доказательством отсутствия роли у живого NPC.

## Перезапуск

- В **15:22:25 UTC** отправлен `stop`; в **15:22:27** подтверждены сохранение
  чанков, `Genesis stopped`, `Shutdown completed!`, завершение Gradle с кодом 0.
- Развёрнут подготовленный JAR с plugin-журналированием результатов осмотра;
  до развёртывания `build verifyPluginJar`/342 JUnit PASS.
- Новый сервер 0.7.4 загрузился в **15:22:45 UTC**; журнал
  `run/logs/2026-10-25_18-22-36_server.log`. WorldConfig UUID сохранился.
- Первое повторение `entity dump` после Booted вернуло **Target not found**.
  Это активный UUID lookup; без проверки загрузки нужного чанка результат
  не доказывает ни потерю NPC, ни его успешное восстановление.

В **15:26:25 UTC** после повторного входа игрок выполнил `/genesiscompat`.
Пользователь прислал скриншот; plugin log независимо сохранил результат:

| Поле | После restart | Сравнение |
| --- | --- | --- |
| World UUID | `634dbbc2-0c16-4a54-8239-01c153f7f299` | Совпал |
| Entity UUID | `c116a72e-1b2c-399d-b1eb-3aa009de6366` | Совпал |
| Lookup / role / model / reserved | `true` / `Fox` / `Fox` / `false` | Совпали |
| Store thread | `true`, `WorldThread - default` | Подтверждён живой callback |
| Ref | `Ref{store=...Store@1977862430, index=3}` | Новое представление; до restart другой Store, index 152 |
| Storage | `DiskResourceStorageProvider$DiskResourceStorage` | Тот же класс; это не квалификация Genesis provider |

В **15:27:55 UTC** повторён серверный `entity dump` по исходному UUID.
Сущность найдена с тем же Ref, который записал plugin logger. Сравнение
декодированных JSON до/после подтвердило равенство компонентов **UUID, Model,
NPC, Transform, Frozen**, включая scale `1.0020575523376465` и позицию
`(-848.5, 121.0, 283.5)`. Набор сериализованных component keys тоже совпал.
Это сравнение выбранных компонентов, не доказательство полного равенства
всего runtime-состояния. Прежняя лисица не заменялась новым спавном.

**Штатный stop/start одной тестовой Fox с сохранением UUID: PASS 25.10.2026.**
Ранний Target not found не означал потерю: после входа игрока исходный UUID
нашёлся. Это согласуется с отсутствием активного представления до загрузки
нужного чанка, но отдельные chunk/entity LOAD/UNLOAD события не наблюдались.
Их сценарий при работающем сервере этим restart не закрывается.

Этот stop/start не является crash recovery, принятием управления или проверкой
сохранений Genesis. Прочие виды, настоящий unload/load при работающем сервере,
поздний наблюдатель, смена роли/snapshot/rollback и права остаются TODO.

Следующий прогон: [EntitySection LOAD и перепривязка Fox → Fox](stage-0-v07-lifecycle-probe.md)
и естественный цикл chunk UNLOAD → LOAD подтверждены отдельно 25.10;
все сериализованные компоненты совпали. Это не полная приёмка смены роли/
rollback, других видов или сохранений Genesis.
