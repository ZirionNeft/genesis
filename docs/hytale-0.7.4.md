# Переход на Hytale 0.7.4

Дата: **25.10.2026**. Цель проекта — ровно **Hytale Release 0.7.4**,
Genesis `0.1.0-SNAPSHOT`, Java 25. GDD и нормативные JSON не менялись.
**Сборка, серверный запуск и двойной осмотр одной тестовой Fox PASS;
штатный restart той же Fox с сохранением UUID PASS 25.10.2026.**
Прежняя клиентская приёмка D01 на 0.7.2 сохраняется в своих границах.

## Источники и артефакты

- [Официальная документация](https://docs.hytale.com/) указывает Release 0.7.4.
- [Официальный Maven](https://maven.hytale.com/release/com/hypixel/hytale/Server/maven-metadata.xml):
  25.10 `latest`/`release` = `0.7.4`; POM и JAR получены через release repository.
  Для прямого чтения metadata использован User-Agent `Gradle/9.6.0`:
  запрос Python без него вернул HTTP 403, что не означает отсутствие версии.
- [Хотфиксы Update 7](https://hytale.com/news/2026/10/hotfixes-update-7) при проверке
  содержали разделы до 0.7.3 включительно. Отдельного описания изменений 0.7.4
  там не было; назначение патча здесь не выводится из номера версии.

Установленный Launcher JAR после патча совпал с Maven побайтово:

| Поле | Значение |
| --- | --- |
| Implementation-Version | `0.7.4` |
| Revision | `c9581a289f4af6bf3d67755b98a7a9d615548593` |
| Java-Version / Build-Jdk-Spec | `25` |
| SHA-256 JAR | `23fad4c8d42b73b4139540bbae26ca0b8a43388f1e07e7b423d616e81834fa79` |
| Локальный Assets.zip | 3 943 767 842 байта, из обновлённой release-установки |

Корневой manifest Assets.zip содержит только Group/Name, без номера версии.
Поэтому версия пары определяется обновлённой установкой, а не выдуманным полем
архива. Полный hash Assets.zip не вычислялся; отдельные ресурсы хешируются ниже.

## Проверенный API и ассеты

Все **82 выбранных `.class`** побайтово совпали между 0.7.2 и 0.7.4.
Выборка покрывает Hytale imports Java-исходников/тестов на момент перехода и отдельно
исследованные классы 0A/анатомии с вложенными классами. Полный список и хеши:
[машинный отчёт](hytale-0.7.4-api-comparison.json).

В частности, совпали UUIDComponent/WorldConfig/World/EntityStore/Store,
RoleChangeSystem/NPCEntity/Role, AbstractPlayerCommand/TargetUtil,
IResourceStorage/DiskResourceStorageProvider, EntityModule, модели/attachments,
BasicCustomUIPage, AnimationUtils и PlayerInteractEvent. Это позволяет сохранить
выводы [статического аудита 0A](stage-0-v07-api-audit.md) для исследованных путей:
Ref не постоянный ключ, смена роли использует UNLOAD/LOAD, bare native пока
NOT_QUALIFIED. Совпадение не доказывает совместимость всего API/протокола,
реальные потоки событий, ownership, durability или поведение клиента.

[Новый инвентарь](stage-0-v07-vanilla-inventory-0.7.4.json) повторно нашёл
12 видов / 16 кандидатов Role ID. Весь массив `species` совпал с отчётом 0.7.2:
пути, хеши выбранных role/model/непосредственных model/texture файлов и сведения
о непосредственно объявленных клипах не изменились. Содержимое файлов анимаций,
наследованные модели и исполнение NPC DSL этим сравнением не проверяются.
Все runtime eligibility/adoption/rig по-прежнему UNVERIFIED.

```sh
python3 tools/audit_hytale_v07_assets.py \
  --assets /absolute/path/to/Assets.zip \
  --server-jar /absolute/path/to/HytaleServer.jar \
  --expected-server-version 0.7.4 \
  --output docs/stage-0-v07-vanilla-inventory-0.7.4.json
```

Скрипт требует точного совпадения версии JAR (по умолчанию 0.7.4);
для воспроизведения старого отчёта нужен явный `--expected-server-version 0.7.2`
и прежние входы. Проверка не является runtime allowlist.

## Сборка и живой сервер

`hytaleVersion=0.7.4` в `gradle.properties` задаёт compileOnly/test/runtime
зависимости и точный `ServerVersion: =0.7.4` готового JAR. Диапазон не расширен;
настоящий PluginManifest codec проверяет отказ соседним версиям.
Изменений Java для компиляции на этом патче не потребовалось.

25.10 выполнено на **JDK 25**:

```sh
./gradlew build verifyPluginJar testGddCatalogValidator verifyGddCatalogs --console=plain
```

BUILD SUCCESSFUL: **342 JUnit**, **34 Python** без failures/errors/skipped,
нормативные каталоги валидны. Перекомпиляция Java, тесты и processResources
выполнены, а не только взяты UP-TO-DATE с 0.7.2.

Сервер 0.7.2 штатно остановлен в 15:03:26 UTC. Перед первым запуском 0.7.4
создана копия `run/universe`, config и permissions в игнорируемом
`run/backups/pre-0.7.4-2026-10-25/`; credentials в копию не включались.

0.7.4 запущен с новыми Assets.zip на `127.0.0.1:5520`,
`-PgenesisCompatibilityProbe=true`, без AnatomyProbe и без debugger.
Первый запуск достиг Booted, но stdin консоли был закрыт способом запуска.
JVM завершена через SIGTERM/shutdown hook: журнал подтвердил `Shutdown completed!`.
Стенд перезапущен с интерактивной консолью; журнал этого исходного запуска:
`run/logs/2026-10-25_18-08-33_server.log` (имя MSK, строки UTC).

- 15:08:34 — Server 0.7.4/revision и asset pack Genesis загружены.
- 15:08:40 — Genesis стартовал, определения v0.7 загружены, plugin enabled.
- 15:08:40 — NPC validation завершена; загружены две Generic-конфигурации Genesis.
- 15:08:41 — `Hytale Server Booted! [Multiplayer]`, около 7,5 с.
- 15:08:54 — `auth status` подтвердил Authenticated (mTLS + JWT), session token
  присутствует; `help genesiscompat` вернул команду и английское описание.

В запуске, как на 0.7.2, есть `[SERR] Reallocate: 131072 to 1179648` уровня
SEVERE; источник не установлен. Сервер продолжил загрузку. Прогон не объявляется
проверкой отсутствия любых ошибок или клиентской приёмкой.
Есть и предупреждения vanilla NPC validation об отсутствующих клипах у ряда
ванильных моделей. Это не принимается как готовность всех видов для Genesis;
до включения вида всё равно нужны его собственные проверки.

Сервер оставлен для клиентского осмотра. **0A IN_PROGRESS, D13 OPEN**:
двойной осмотр и штатный stop/start одной командно созданной Fox приняты;
отдельно принят [цикл chunk UNLOAD → LOAD этой Fox](stage-0-v07-lifecycle-probe.md)
на работающем сервере и перепривязка Fox → Fox. Нужны другие субъекты/виды,
потоки/lifecycle, безопасное принятие/восстановление и права чужого controller.
Прежний прогон загрузки 0.7.2 — [отдельная запись](stage-0-v07-live-probe.md).
Сохранённый UUID этой тестовой Fox подтверждён. Игровой допуск вида остаётся
UNVERIFIED; приёмка сохранений Genesis и crash recovery ещё TODO.

## Первый клиентский прогон диагностики

25.10 пользователь сообщил «Сделал» после инструкции выполнить два осмотра.
В текущем журнале подтверждены вызовы `/genesiscompat` в **15:13:54** и
**15:14:21 UTC**. Это свидетельство подключения игрока и отправки команд;
само по себе оно не доказывает успешную наводку, permission или совпадение UUID.
В исходной команде результат отправляется только игроку. Пользователь прислал
скриншот: оба осмотра дали одинаковые world/entity UUID, `lookup=true`, Fox/Fox,
reserved=false и DiskResourceStorage. Серверный dump подтвердил исходный UUID.
[Полные значения, границы и restart-прогон](stage-0-v07-fox-live.md).

В команду добавлена запись результата в plugin logger: inspected с UUID,
role/model/storage/Ref и store thread, либо причина отказа. Геном/кандидат,
владение и компоненты по-прежнему не меняются; игрок и скрытые генетические
данные не добавляются в диагностику. Это журнал технического стенда,
не постоянное хранилище Genesis и не доказательство происхождения NPC.
`build verifyPluginJar` после изменения PASS: 342 JUnit без ошибок/пропусков.
После получения скриншота обновлённый JAR **развёрнут** через штатный stop/start:
новый сервер Booted в 15:22:45 UTC, журнал `2026-10-25_18-22-36_server.log`.
Первый активный поиск до повторного входа игрока вернул Target not found;
после входа игрока в 15:26:25 UTC найден прежний UUID, lookup=true, Fox/Fox.
Plugin log подтвердил storeThread=true и WorldThread/default, новый Ref index=3
вместо прежнего 152. Серверный dump в 15:27:55 подтвердил равенство
UUID/Model/NPC/Transform/Frozen до/после. WorldConfig UUID тоже сохранился.
Подменный спавн не использовался. Штатный restart этой Fox PASS;
настоящие chunk LOAD/UNLOAD в этом restart-прогоне не наблюдались.
Позднее 25.10 они подтверждены [отдельным сценарием](stage-0-v07-lifecycle-probe.md):
UNLOAD после выхода игрока, отсутствие активного lookup, затем LOAD после
обратного входа, прежние UUID/lookup/Fox/Fox и полное равенство сериализованного
снимка. Это не crash recovery/приёмка Genesis store.
