# 0A: загрузка и перепривязка роли тестовой Fox

Дата: **25.10.2026**. **0A IN_PROGRESS, D13 OPEN**.
Hytale **0.7.4**, тот же NPC из [проверки restart](stage-0-v07-fox-live.md):
world `634dbbc2-0c16-4a54-8239-01c153f7f299`,
entity `c116a72e-1b2c-399d-b1eb-3aa009de6366`.
Субъект создан командой, имеет Frozen, остаётся техническим NPC без генома,
WildCandidateRecord, прав питомца и первооткрытий.

## Метод наблюдения

Предыдущий сервер штатно остановлен в **15:33:02 UTC**. Новый запуск —
`run/logs/2026-10-25_18-33-27_server.log`, с read-only probe и локальным JDWP.
Использованы несуспендирующие logpoints IntelliJ на закреплённом JAR:

- `EntityStore.UUIDSystem.onEntityAdded/onEntityRemove`: UUID, Add/RemoveReason,
  Ref, store thread и стек; фильтр по UUID тестовой лисицы.
- `RoleChangeSystem.tick`: UUID/исходная роль/запрошенный индекс до remove,
  UUID/роль/валидность старого Ref/новый Ref/Frozen после add.

Выражения только читают значения; мира/компонентов/состояния через evaluator
не меняли. Поток мира не останавливали. Вывод получен через DRAIN_EVENTS,
ошибок breakpoints в описанных воспроизведениях нет.
Для действий использованы штатные серверные команды.
После завершения сценария все четыре agent-logpoints удалены, сессия
`Genesis compatibility 0A attach` отключена, временная attach configuration
удалена. Две исходные пользовательские exception-breakpoints сохранились
выключенными. Активных debug sessions не осталось; сервер продолжил работу,
что отдельно подтверждено ответом консольного `who` после отключения debugger.

## Загрузка активного представления

Позиция Fox `(-848.5, 121, 283.5)`, размер стороны чанка в артефакте — 32,
координаты колонки `(-27, 8)`.

В **15:34:29 UTC** выполнена `chunk load -27 8 --world default`, без markdirty.
Консоль сообщила Done loading, но следующий `entity dump` по UUID дал not found;
событие ADD для Fox тогда не зафиксировано. Успех загрузки колонки сам по себе
нельзя принимать за активную загрузку всех её сущностей/секций.

После входа игрока в **15:36:31.030 UTC** отладчик подтвердил:

```text
ADD uuid=c116a72e-1b2c-399d-b1eb-3aa009de6366 reason=LOAD
ref=Store@798191985/index=3
inThread=true, thread=WorldThread - default
```

Стек: `ChunkGrid.getChunkSectionReferenceAsync` →
`EntitySection.EntitySectionLoadingSystem.onComponentRemoved` →
`Store.addEntities` → `EntityStore.UUIDSystem.onEntityAdded`.
Это наблюдение реального пути загрузки EntitySection после входа игрока,
а не доказательство полного цикла unload/load при работающем сервере.

## Перепривязка Fox → Fox

В **15:37:32 UTC** получен исходный серверный `entity dump` по UUID.
В **15:37:58 UTC** выполнено:

```text
npc role Fox --entity c116a72e-1b2c-399d-b1eb-3aa009de6366 --world default
```

Это намеренно перепривязка **той же** роли: она проверяет queue/remove/add,
не объявляет проверенной смену на другую роль или принятие управления Genesis.
В исходнике команды проверяется roleChangeRequested и вызывается штатный
`RoleChangeSystem.requestRoleChange`, а не прямой setter.
Ответ `Set NPC role` отправляется после помещения запроса в очередь;
сам ответ не доказывает успешное завершение перехода. Здесь переход отдельно
подтверждён четырьмя runtime-событиями и последующим dump.

| Время UTC | Наблюдение debugger |
| --- | --- |
| 15:37:58.892 | Перед remove: UUID исходный, from=Fox, toIndex=978, Ref index=3 |
| 15:37:58.921 | REMOVE reason=UNLOAD; стек проходит через RoleChangeSystem.tick |
| 15:37:58.950 | ADD reason=LOAD; UUID исходный, новый Ref index=271; тот же путь смены роли |
| 15:37:58.975 | После add: role=Fox, oldRefValid=false, новый Ref валиден, frozen=true |

Оба UUID-callback выполнены при `store.isInThread()=true` в
`WorldThread - default`. Индекс роли 978 — значение текущего runtime registry,
не сохраняемый ID и не допуск вида. Сохраняемая ссылка на роль — `Fox`.

В **15:38:35 UTC** повторный dump нашёл исходный UUID, role/model Fox,
scale `1.0020575523376465`, прежнюю позицию. Сравнение декодированных JSON
подтвердило равенство **всех сериализованных компонентов** этого снимка,
включая UUID/Model/NPC/Transform/Frozen и набор component keys.
К этому моменту Ref отображал index=71; индекс не фиксируется как identity.
Отдельная новая пара ADD/REMOVE между этими наблюдениями не зафиксирована.

**Перепривязка Fox → Fox: PASS 25.10.2026 в указанных границах.**
Старый Ref действительно недействителен; для следующих действий нужен поиск
по постоянному UUID. UNLOAD/LOAD здесь нельзя трактовать как смерть/новое
рождение или выдавать новый геном/награду. Сериализованный снимок совпал,
но это не полный snapshot/rollback runtime AI, timers, messaging и evaluator:
исследованный движок очищает ряд таких компонентов при смене роли.

## Отдельная выгрузка чанка

В 15:41:48 UTC `who` подтвердил одного игрока в default; принудительную
выгрузку его чанка в этом состоянии не выполняли.

После выхода игрока в **15:42:42.709 UTC** отладчик зафиксировал:

```text
REMOVE uuid=c116a72e-1b2c-399d-b1eb-3aa009de6366 reason=UNLOAD
ref=Store@798191985/index=71
inThread=true, thread=WorldThread - default
```

Стек: `ChunkUnloadingSystem.tryUnload/tick` → `ChunkGrid.remove` →
`EntitySection.EntitySectionLoadingSystem.onComponentAdded` →
`Store.removeEntities` → `EntityStore.UUIDSystem.onEntityRemove`.
RoleChangeSystem в этом стеке отсутствует; сервер не перезапускался.
Принудительный `chunk unload` не потребовался.

В **15:44:52 UTC** консольные `who` и `entity dump` по исходному UUID
подтвердили `default (0): (empty)` и `Target ... not found`.
**Естественная выгрузка этой Fox и исчезновение активного lookup: PASS.**
Это деактивация представления, не смерть и не удаление постоянной записи Genesis.
Тот же Add/RemoveReason из RoleChangeSystem эту проверку не заменяет.

В **15:48:46.557 UTC** после повторного входа игрока зафиксирован обратный
**ADD reason=LOAD** с исходным UUID, `Store@798191985/index=3`,
`inThread=true`, `WorldThread - default`. Стек снова прошёл через
`ChunkGrid.getChunkSectionReferenceAsync` →
`EntitySection.EntitySectionLoadingSystem.onComponentRemoved` →
`Store.addEntities` → `EntityStore.UUIDSystem.onEntityAdded`.
Это тот же сервер/Store, между UNLOAD и LOAD restart не было.

В **15:48:58 UTC** `/genesiscompat` сохранил в plugin log исходные world/entity
UUID, `lookup=true`, role/model `Fox`, `reserved=false`, storeThread=true.
В **15:49:25 UTC** серверный dump по исходному UUID независимо подтвердил
состояние. Декодированный JSON полностью совпал со снимком перед выгрузкой
(после перепривязки роли): тот же набор и значения **всех сериализованных
компонентов**, включая UUID/Model/NPC/Transform/Frozen, scale и позицию.
Подменный спавн не использовался.

**Естественный цикл UNLOAD → LOAD этой Fox на работающем сервере:
PASS 25.10.2026.** Активный lookup отсутствовал между событиями и восстановился
после LOAD. Прежний индекс 3 был использован повторно; индекс/Ref не являются
постоянной идентичностью и не заменяют world/entity UUID.
Сериализованный снимок не является полным runtime snapshot AI и не доказывает
crash recovery/целостность будущего Genesis store.

## Что остаётся до D13

Следующий пакет: [переход в другой AI Role ID и собственный marker](stage-0-v07-role-marker-probe.md)
принят отдельно 25.10. Alias наследует Fox; новое поведение/rig и полный AI
rollback остаются непроверенными. Список ниже — исходные границы этого прогона.

- Переключение на другую роль с сохранением нужных компонентов и согласованных
  model/rig/AI bindings; полный snapshot/rollback и восстановление при ошибке add.
- Собственный namespaced component через переходы; в этом прогоне его не добавляли.
- Проверяемый допуск конкретного NPC, внешний controller/owner и отказ чужому.
- Волк/остальные виды, поздний наблюдатель, игровые действия и сохранения Genesis.

Прототипное наблюдение не включает приручение и не квалифицирует native provider.
Java/ассеты/GDD в этом прогоне не изменялись. Последние build/verifyPluginJar и
342 JUnit уже PASS; новые игровые свидетельства проверяют другие условия.
