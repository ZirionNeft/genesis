# GDD v0.7: сверка с текущими наработками

Дата: **23.10.2026**. Проверены RU/EN GDD, все шесть JSON (пять нормативных
каталогов и конфиг), текущие Java-классы/ресурсы, Gradle и профильные отчёты.
Это карта перехода, не заявление о реализации новых механик.
Исходные файлы GDD, Java, тесты и игровые ассеты при актуализации не изменялись.
Ниже сохранена исходная сверка; реализация от 25.10 отмечена в конце и в плане.

## Источники контракта

| Источник | Назначение |
| --- | --- |
| [RU](../specs/Genesis_GDD_v0.7/Genesis_GDD_RU_v0.7.md), [EN](../specs/Genesis_GDD_v0.7/Genesis_GDD_EN_v0.7.md) | §§1–12: игровой/технический контракт и приёмка |
| [Gene Schema v2](../specs/Genesis_GDD_v0.7/Genesis_GDD_v0.7_Gene_Schema_v2.json) | 23 общих гена, семейные, HERITAGE, варианты/ID |
| [Hybrid Graph v1](../specs/Genesis_GDD_v0.7/Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json) | 4×7 форм, 112 уникальных пар, исторический union roots |
| [Expression Rules v2](../specs/Genesis_GDD_v0.7/Genesis_v0.7_Stage_10_Expression_Rules_v2.json) | Полные pairRules, founder weights, статы, utility, effective форма |
| [Morphology Set v2](../specs/Genesis_GDD_v0.7/Genesis_GDD_v0.7_Morphology_Set_v2.json) | Семейные rig, 14 слотов, три возраста, явные bindings |
| [Content Catalog v1](../specs/Genesis_GDD_v0.7/Genesis_v0.7_Stage_08B_Content_Catalog_v1.json) | 28 форм, 21 мутация, 7 ролей, 5 заклинаний, 8 заданий |
| [Server Config](../specs/Genesis_GDD_v0.7/Genesis_GDD_v0.7_Server_Config.json) | Единый источник стартовых серверных значений |

v0.7 заменяет v0.5 как целевую спецификацию. Имена Stage в JSON не являются
этапами нашего плана. Числа нормативны как стартовый дизайн, но ещё не проверены
плейтестами или адаптером Hytale. Версия игры остаётся закреплённой в
`gradle.properties`: 0.7.2, Java 25, мод 0.1.0-SNAPSHOT.

## Что сохраняет ценность и что требуется заменить

| Область | Фактическая реализация | Изменение для v0.7 |
| --- | --- | --- |
| Bootstrap/JAR | Небольшой GenesisPlugin, lifecycle logging, опциональная dev-команда; core не подключён к игровому циклу | Сохранить основу; не считать plugin setup доказательством реализованных механик |
| Анатомия D01 | Принятый 16.10 модульный wolf/fox-стенд, цельный контроль, Idle/Walk и сетевые проверки | Переиспользовать технический опыт; новые family rigs/слоты/возрасты/ассеты проверять отдельно |
| Геном | [Locus](../src/main/java/dev/ifelse/zirion/genesis/core/genetics/Locus.java): 12 enum-локусов; Genome/GeneticsRuleset обходят этот enum | 26/27/27/28 пар с динамическими namespaced генами и HERITAGE; простого добавления контентной ревизии недостаточно |
| Контент | CanidStarterContent и CanidBreedingContent: только wolf/fox, canids_draft@1/@2, 30 аллелей/54 пары у кандидата @2 | Нормативные ID/схема v2 и 12 исходных видов; draft@2 не означает соответствия geneSchema=2 |
| Наследование/RNG | Независимое наследование, GenerationSeed с раздельными потоками и чистый BreedingWorkflow | Сохранить принцип детерминизма, адаптировать входы/порядок/версии/векторы; это ещё не транзакция выдачи Искры |
| Граф форм | BreedingCompatibility проверяет family/rig/ruleset; workflow не выбирает форму ребёнка | Добавить 28 узлов/112 пар и исторический union roots; effective форма отдельно по HERITAGE |
| Физиология | [PhenotypeRules](../src/main/java/dev/ifelse/zirion/genesis/core/phenotype/PhenotypeRules.java): заданная archetype-база × сумма modifiers, clamp по Range; 10 внутренних Stat | Семь физических статов от шести HERITAGE-копий исходных видов, percent/flat/minimum/HALF_UP; utility отдельно, гибридное имя не источник базы |
| Мутации | MutationPolicy.gddDefaults: 4/2.5/1/0.5%, общий 8%; шесть целей draft@2, impact draw/SKIP | 21 тип, 5/4.5/2.5% и магия 6/12/22%; eligibility, максимум одно событие, исследование и одноразовая mastery-гарантия |
| Родословная | Pedigree/SpeciesAncestry, интервалы неполных данных; InbreedingPolicy и workflow включают frailty/soft/strict gates | Родословную оставить; ancestry не заменяет HERITAGE; дополнительных штрафов или kinship-отказов нет |
| Морфология | Exact signature → Appearance, отказ при отсутствии; у draft 864 сигнатуры и 0 финальных планов | Явные `(rigId, slot, visualState, age)`, founder support, NONE и новые проверки покрытия; 864 не переносится на v0.7 |
| GEN1 | [GenesisCodeCodec](../src/main/java/dev/ifelse/zirion/genesis/core/code/GenesisCodeCodec.java): GEN1-Base32-checksum, бинарная schema=1, усечённый SHA-256, лимит payload 65536 | GEN1.Base64url.CRC32, RFC8785 JSON, fingerprints точных реестров, лимиты 8192 bytes/12000 chars и 16 extensions по config; прямой совместимости нет |
| Расширения/API | Immutable bytes, opaque payload, registry freeze, fail-closed reproduction; workflow отклоняет любое наследование payload | Принципы сохранить; определить стабильный API и наследование payload. Наличие codec не означает готовый API или разрешённый импорт |
| NPC/приручение | Только собственные TestNPC/AnatomyProbe; настоящего taming нет | Отдельный WildCandidateRecord при осмотре; принятие того же ванильного NPC без двойника; безопасность до начала/commit, release прежнего поведения |
| Искры | Чистый генетический результат без предмета/записи/store | Один успех → одна постоянная Искра, 16/48 слотов, KEEP неизвестного, нулевой возврат, одноразовое воплощение |
| Рост/XP | Код отсутствует; документы 18.10 задавали рост от XP | По v0.7 возраст идёт от времени/ухода; infant 15 мин → juvenile 45 мин → adult; offline ≤240 мин. XP отдельно, 1–30; adult level 1 допустим |
| Смерть/вызов | Код отсутствует | Одна проекция creatureId, combat summon gate, три deathPolicy, бесплатное восстановление/maxRevives, сохранение груза |
| Сохранения | Постоянного store/операций/outbox нет | Auto/native/sqlite/postgresql, CAS/revision/activationEpoch, журнал/outbox, reconciliation; ECS и store раздельны |
| Роли/гайд | Код отсутствует | 7 ролей, 2 ролевых слота, 12/20/28; 1 врождённое заклинание ARCANA+ELEMENT с age/training gate; 8 заданий и возобновляемый гайд |

Старые алгоритмы не удалены: при этой задаче актуализированы документы,
а не реализован весь новый мод. Их дальнейшее использование возможно только
с явным отделением исторического прототипа от нового baseline; v0.7 не обещает
поддержку genetic v1 и не предусматривает её migration provider. Нельзя молча
переписать смысл прежних ID/байтов или объявить старый codec новым GEN1.

## Проверка согласованности пакета v0.7

Проверено локальным Python-разбором исходных JSON без их изменения:

- Все шесть JSON разбираются. В графе 28 форм, по 28 уникальных пар на группу
  (включая одинаковые формы), всего 112; матрицы полные, child roots равны
  объединению parent roots для каждой пары.
- В expression rules 26 определений не-HERITAGE генов (23 общих и три семейных);
  у каждого полная таблица неупорядоченных пар заявленных вариантов. Это не
  полный геном одной особи: HERITAGE и допустимые семейные гены считаются отдельно.
- Четыре SHA-256 в config совпали с SHA-256 JSON в компактном представлении
  с сортировкой ключей; ссылки expression на graph и content catalog совпали
  с тем же представлением. Это проверка данного пакета, **не** готовый RFC8785
  encoder; хеширование сырых pretty-printed файлов даёт другие значения.
- Сумма XP по формуле для переходов 1→30 — 17038, как в RU/EN.

**V07-01 — DONE 25.10.2026: HORN_FORM у Бегунов.** Первоначальный конфликт: Gene Schema `familyGenes`,
Morphology Set `families`, Content Catalog `familyGeneDefinitions.HORN_FORM`
и Expression Rules `families.genesis:runners.familyGenes` включают этот ген.
Expression Rules `genes.HORN_FORM.families` содержал только `genesis:heavies`.
По прямому поручению пользователя добавлен `genesis:runners`; применимость теперь
совпадает со схемой/контентом/morphology и разделом families тех же rules.
Обновлён только expression fingerprint в Server Config:
`68f05abe8ec991f1229a93d662abfc50104f5d4070a31bb94a0c15f5850b06d7`.
Остальные четыре каталога, ID/версии и правила пар не изменены. Это исправление
не принятого ранее пакета; горячей подмены зарегистрированных правил нет. Точная ссылка на поле: [Expression Rules](../specs/Genesis_GDD_v0.7/Genesis_v0.7_Stage_10_Expression_Rules_v2.json).

Проверки выше не сертифицируют полную семантику всех каталогов, реальный
Hytale API, ассеты, боевое поведение или транзакционность native store.
В графе vanillaRoleId исходных видов остаётся null, а morphology явно
указывает `assetIntegrationValidatedInHytale=false`.

## Сборка и состояние проверки

23.10.2026 выполнено `build verifyPluginJar` с JDK 25 из IDEA: BUILD SUCCESSFUL.
Gradle использовал UP-TO-DATE для Java/resources/test; `verifyPluginJar`
выполнился. Существующие JUnit XML: 18 suites, 149 tests, 0 failures/errors/skipped.
Это не новый прогон тестов и не проверка правил v0.7. После документационных
правок сервер/клиент не запускались и ассеты не производились.

Свидетельства D01 от 16.10 и проверки удаления забракованных ассетов от 18.10
сохраняют историческую силу в своих границах. Ни одна новая игровая механика
не отмечена DONE из-за успешной сборки или обновления документов.

Порядок перехода, критерии этапов и пересмотр D01–D15 — в
[актуальном плане](implementation-plan.md). Исторические stage-1 отчёты
описывают фактический прототип v0.5; они не нормативные таблицы v0.7.

Повторная проверка полноты и порядка плана 23.10.2026 —
[аудит пропущенных шагов](plan-v0.7-audit.md): добавлены 0A/1V/2D,
генератор диких кандидатов, runtime-конфиг, анализ/уход, состояния операций
и явные точки допуска. Это исправления документации, а не выполненные механики.

25.10.2026 сверка автоматизирована [dev-валидатором](stage-1-v07-catalog-validation.md):
исходный пакет даёт FAIL с единственной диагностикой FAMILY_GENE_MEMBERSHIP
для HORN_FORM; 33 теста инструмента PASS. Runtime-модель, конфиг и GEN1 этим
инструментом не реализованы; исходные JSON не менялись.

Следующий пакет 25.10.2026: [immutable-модель генома и наследование v2](stage-1-v07-genome.md)
реализованы в Java; 20 новых JUnit-тестов, полный Python-вектор и 20 000 потомков
PASS. Полный Java-прогон — 169 тестов, плюс 33 Python-теста валидатора.
Загрузка реестров/конфига, граф/phenotype/мутации/GEN1 и игровой workflow v2
ещё TODO; конфликт HORN_FORM не исправлялся и не обходится этой моделью.

Следующий пакет 25.10.2026: [Java-loader/config/snapshots](stage-1-v07-runtime-config.md)
реализованы, шесть источников включены в JAR и читаются startup bootstrap.
Исходный пакет отклоняется по HORN_FORM без публикации; принятый стенд сохраняется.
Typed config/reload и упаковка проверены 42 новыми тестами; полный Java-прогон —
211 тестов, плюс 33 Python-теста. Сервер/клиент не запускались. Workflow/расчёты/
игровые операции и приёмка исходного bundle остаются открытыми.

Исправление V07-01 25.10.2026 по поручению пользователя: исходный пакет принят
Python-валидатором и Java-loader. Bootstrap со штатными ресурсами публикует снимок
в тесте. Удалено временное исправление HORN_FORM из положительных fixtures;
повторное исключение Бегунов отвергается даже с пересчитанным fingerprint.
Старый expression pin в операторском конфиге даёт отказ без перезаписи файла;
существующие конфиги требуют явного обновления этого pin.
`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` PASS: 212 JUnit
и 34 Python-теста без ошибок/пропусков. Java test выполнен при первом запуске,
при повторном — UP-TO-DATE. Сервер/клиент не запускались; игровые механики и
визуальные bindings этим исправлением не приняты.

Следующий пакет 25.10.2026: [граф и разрешение форм](stage-1-v07-forms.md)
реализованы в чистом Core и публикуются через Java-loader в bundle. Историческая
идентичность закрепляет graph ID/version/SHA-256, explicit edge выбирает union
корней. Генетически проявленная форма определяется положительными HERITAGE-копиями
и проверяется на включение в исторические корни. Проверены все 112 пар в обоих
порядках, 2 916 наборов HERITAGE, backcross и следующее поколение. 13 новых тестов
и один дополнительный тест loader; полный прогон — 226 JUnit и 34 Python-теста PASS,
`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` BUILD SUCCESSFUL.
Исходные JSON не менялись, сервер/клиент не запускались. Workflow/мутации/статы/
визуальные bindings и GEN1 ещё не реализованы.

Следующий пакет 25.10.2026: [генетический потенциал](stage-1-v07-phenotype.md)
реализован в чистом Core. Loader публикует immutable expression tables и 12
исходных баз, resolver применяет 111 pairRules, считает семь статов с точным
знаменателем шесть до финального HALF_UP, выдаёт symbolic visual/utility и
необязательный ARCANA+ELEMENT spell potential. Возраст/обучение/роль/экипировка
не входят в потенциал; signature passives и игровые эффекты ещё TODO.
14 новых тестов, независимые Python/Fraction-векторы для всех 111 пар и три
теста loader PASS. Полный прогон — 243 JUnit и 34 Python-теста без ошибок/пропусков;
`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` BUILD SUCCESSFUL.
Исходные JSON/ассеты не менялись, сервер/клиент не запускались.

Следующий пакет 25.10.2026: [workflow v2 до мутаций](stage-1-v07-breeding-workflow.md)
связывает удержанный config/catalog context, GenerationSeed scheme 1, вероятность/
cooldown, наследование, историческую/effective форму и потенциал. Успех возвращает
AwaitingMutation с прямыми ссылками родителей и независимым mutation seed;
готовая Искра/мутация/игровой commit этим не реализованы. Нет kinship/frailty gate.
Повтор удерживает исходные значения после reload. 17 новых workflow-тестов, один
тест runtime context, все 112 пар и 28 независимых Python seed-векторов PASS.
`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` BUILD SUCCESSFUL:
261 JUnit и 34 Python-теста без ошибок/пропусков. Исходные JSON/ассеты не менялись,
сервер/клиент не запускались; полный mutation replay и durable журнал ещё TODO.

Следующий пакет 25.10.2026: [мутации/гарантия и полный генетический workflow](stage-1-v07-mutations.md)
реализованы. Typed catalog загружает 21 ID, evaluator меняет одну отличающуюся
копию без HERITAGE/impact-roll; qualifying focused mastery quiet/quiet ведёт
к гарантии по snapshot config. Research state закреплён по игроку/семейству,
выход — CAS-предложение, а не запись store. Полный Conceived содержит итоговый
геном и пересчитанный потенциал. Ролевые/игровые эффекты и постоянный прогресс ещё TODO.
12 новых тестов evaluator, семь полного workflow и три loader PASS;
42 независимых вектора копий + 28 полных попыток, 120 000 расчётов tiers/ID.
`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` BUILD SUCCESSFUL:
283 JUnit и 34 Python-теста без ошибок/пропусков. JSON/ассеты не менялись,
сервер/клиент не запускались; сериализация журнала/GEN1 и игровой commit ещё TODO.

Следующий пакет 25.10.2026: [генератор диких кандидатов](stage-1-v07-wild-candidates.md)
создаёт immutable-предложение с чистым HERITAGE и полным геномом v2. Частоты
задаются отдельной явной versioned/hash-политикой; candidate-level ARCANA берётся
из config, дозировка пробуждения тоже требует явных весов. Принятой игровой
политики нет, тестовая не подключена. Existing-запись имеет приоритет перед
новым seed/affinity/config; постоянный NPC-ключ, атомарный осмотр и восстановление
после рестарта ещё относятся к 0A/2A. 15 новых тестов и 80 независимых векторов
проверяют все виды/семейные гены, policy hash, RNG bits и полный геном;
40 000 gates проверяют частоты. `build verifyPluginJar testGddCatalogValidator
verifyGddCatalogs` BUILD SUCCESSFUL: 298 JUnit и 34 Python-теста без ошибок/пропусков.
Нормативные JSON/ассеты не менялись; сервер/клиент не запускались.

Следующий пакет 25.10.2026: [выбор signature passive](stage-1-v07-signature-passives.md)
реализован по effectiveSignatureForm и selectedRole. В bundle включён immutable
typed catalog всех 28 определений с точными ссылками. Историческая форма не
даёт пассив потерянной линии; выбор не меняет геном или генетический потенциал.
Численные эффекты пока существуют только в текстовых описаниях; описания
не исполняются. Машинный контракт effect/условий/cooldown, игровые допуски и
применение после потенциала остаются TODO, GDD не менялся.
Восемь тестов Core и пять loader PASS: все 28 форм, 2 916 HERITAGE-назначений
× семь ролей, backcross, immutable snapshots, invalid data и отсутствие текстового
исполнения. Полный прогон `build verifyPluginJar testGddCatalogValidator
verifyGddCatalogs` BUILD SUCCESSFUL: 311 JUnit и 34 Python-теста без ошибок/пропусков.
Ассеты не менялись, сервер/клиент не запускались. Следующий независимый пакет — GEN1.

Следующий пакет 25.10.2026: [новый GEN1 codec](stage-1-v07-genesis-code.md)
реализован в чистом `core.code.v2`. Закрытая payload-схема 2 задаёт все поля,
порядок пар/расширений, JCS/UTF-8, signed int64 seed строкой, Base64url без
padding и CRC32 по payload bytes. Лимиты берутся из Server Config; opaque bytes
и неизвестные pins сохраняются транспортом, а GeneticBlueprintResolver
отдельно требует точных definitions/всего генома и отклоняет unresolved extensions.
Назначенные registry fingerprints не пересчитываются по JCS под прежними ID.
Старый GEN1-... не принимается; игровые permissions/analysis/assets/store
остаются отдельными допусками, команд импорта/экспорта пока нет.
13 новых тестов, 31 независимый полный Python-вектор для всех 28 форм и три
JCS-вектора PASS. `build verifyPluginJar testGddCatalogValidator verifyGddCatalogs`
BUILD SUCCESSFUL: 324 JUnit и 34 Python-теста без ошибок/пропусков.
Нормативные JSON/ассеты не менялись; сервер/клиент не запускались.

Следующий пакет 25.10.2026: [чистые знания/публичный паспорт](stage-1-v07-knowledge.md)
реализованы в `core.knowledge`. Visual observations и analyzed genes независимы;
scope закреплён по игроку, субъекту и hash blueprint. Обновления — immutable
CAS-предложения, не запись store. PublicPassport содержит только subject и
наблюдённые visual tokens; AnalysisView отдельно выдаёт только изученные пары.
Full GEN1 требует READ_FULL_CODE и after_analysis/always; owner export — ещё
capability/флага. Permissions и завершённые действия должны приходить от будущего
авторитетного адаптера, а не от клиента. Хранение/настоящие права/игровой анализ,
сравнение/фильтр/UI и actual-age visibility ещё TODO.
16 новых тестов PASS: hidden-carrier noninterference/сериализация, все 28 форм,
права/флаги/scope, монотонные facts, invalid data, immutable/reload/parallel reads.
`build verifyPluginJar testGddCatalogValidator verifyGddCatalogs` BUILD SUCCESSFUL:
340 JUnit и 34 Python-теста без ошибок/пропусков. GDD/JSON/ассеты не менялись,
сервер/клиент не запускались. Ближайший шаг — 0A/D13 перед подключением 2A/2B.

Следующий пакет 25.10.2026: [0A/D13 — аудит API и стенд](stage-0-v07-api-audit.md).
Локальный JAR побайтово совпал с закреплённым Maven 0.7.2. Статический инвентарь
нашёл 16 candidate Role ID для 12 видов; rig/ownership/adoption остаются UNVERIFIED.
UUID codec сохраняет existing ID, но missing field генерирует новый UUID — два
теста PASS. RoleChangeSystem использует UNLOAD/LOAD Holder и новый Ref; это нельзя
считать смертью/рождением. Bare DiskResourceStorageProvider в ряде ошибочных
случаев возвращает default resource и пока NOT_QUALIFIED для native/auto.
Добавлен opt-in read-only `/genesiscompat` с отдельным permission, без изменения
NPC/создания кандидата. 0A IN_PROGRESS, D13 OPEN: live UUID/restart/adoption/права
ещё не проверены. `build verifyPluginJar testGddCatalogValidator verifyGddCatalogs`
BUILD SUCCESSFUL: 342 JUnit и 34 Python-теста без ошибок/пропусков.
Исходные GDD/JSON и игровые модели не менялись; сервер/клиент не запускались.

Следующий пакет 25.10.2026: [живой запуск read-only стенда](stage-0-v07-live-probe.md)
на 0.7.2 подтвердил загрузку Genesis/определений, auth и регистрацию команды.
После сообщения пользователя о патче прогон штатно остановлен; клиентский осмотр
NPC не состоялся. Временные agent-logpoints/attach config удалены.

Затем по сообщению пользователя выполнен [переход на Hytale 0.7.4](hytale-0.7.4.md).
Точная версия зависимости/манифеста обновлена без расширения диапазона.
Установленный JAR совпал с официальным Maven, 82 выбранных класса API побайтово
совпали с 0.7.2; новый инвентарь 12 видов/16 ролей совпал по массиву species.
Создан backup dev-мира, сервер 0.7.4 с обновлёнными Assets.zip загрузил Genesis,
pack, определения и две Generic-роли; auth status/help genesiscompat PASS.
Полный прогон `build verifyPluginJar testGddCatalogValidator verifyGddCatalogs`
BUILD SUCCESSFUL: 342 JUnit и 34 Python без ошибок/пропусков.
План/README/AGENTS обновлены; GDD/JSON/игровые модели не менялись.
0A IN_PROGRESS, D13 OPEN: клиент 0.7.4, UUID существующего NPC после рестарта,
принятие/lifecycle/права/store ещё TODO. D01 DONE остаётся приёмкой на 0.7.2.

Далее 25.10.2026 [двойной осмотр одной тестовой Fox](stage-0-v07-fox-live.md)
подтверждён скриншотом пользователя и серверным dump: одинаковые world/entity UUID,
lookup=true, role/model Fox, reserved=false. NPC создан командой, в снимке есть
Frozen; эти наблюдения не принимают естественное появление/AI/taming/ownership.
Добавлено plugin-журналирование результатов/отказов, `build verifyPluginJar` PASS,
342 JUnit без ошибок/пропусков. После получения baseline обновлённый JAR развёрнут
через штатный stop/start, WorldConfig UUID сохранился. Первичный активный поиск
entity после Booted вернул not found; без повторной загрузки чанка это не доказывает
потерю или сохранность NPC. Ожидается вход игрока/осмотр прежней лисицы,
entity UUID после restart остаётся TODO. GDD/JSON/ассеты не менялись.

В 15:26:25 UTC 25.10.2026 пользователь повторно осмотрел прежнюю Fox после
входа на перезапущенный сервер. Скриншот и plugin log подтвердили исходные
world/entity UUID, lookup=true, Fox/Fox, reserved=false; callback выполнен
в WorldThread/default с storeThread=true. Ref получил другой Store/index
(152 → 3). Серверный dump в 15:27:55 подтвердил равенство выбранных компонентов
UUID/Model/NPC/Transform/Frozen, включая scale/позицию. Штатный stop/start этой
тестовой Fox **PASS**; ранний not found не означал потерю NPC. [Подробности](stage-0-v07-fox-live.md).
0A IN_PROGRESS/D13 OPEN: настоящая выгрузка чанков, другие виды, смена роли,
snapshot/rollback/принятие/права и сохранения Genesis ещё TODO.

Следующий runtime-прогон 25.10.2026: [LOAD и перепривязка Fox → Fox](stage-0-v07-lifecycle-probe.md).
Несуспендирующие logpoints закреплённого JAR подтвердили загрузку EntitySection
после входа игрока, а затем native RoleChangeSystem: UNLOAD/LOAD, прежний UUID,
oldRefValid=false, новый Ref, WorldThread/storeThread=true. Все сериализованные
компоненты серверных JSON до/после перепривязки совпали. Это не переход на другую
роль и не полный rollback AI. `chunk load` колонки сообщил успех без активного
lookup Fox; отдельный unload/load ещё TODO, для него запрошен выход игрока.
Java/GDD/JSON/ассеты не менялись; 0A IN_PROGRESS, D13 OPEN.

В 15:42:42.709 UTC после выхода игрока debugger отдельно подтвердил реальную
выгрузку той же Fox: ChunkUnloadingSystem → ChunkGrid.remove → EntitySection →
Store.removeEntities → UUIDSystem, причина UNLOAD, WorldThread/storeThread=true.
Сервер не перезапускался, принудительная выгрузка не понадобилась. `who` и
UUID lookup в 15:44:52 подтвердили 0 игроков и отсутствие активного представления.
Обратная загрузка после этой выгрузки ещё ожидается: пользователь приглашён
снова войти, несуспендирующие probes оставлены для завершения сценария.

В 15:48:46.557 UTC 25.10.2026 debugger подтвердил обратный LOAD той же Fox
через EntitySection на том же сервере/Store без restart. Plugin log осмотра
в 15:48:58 и серверный dump в 15:49:25 подтвердили прежние world/entity UUID,
lookup=true, Fox/Fox, reserved=false и WorldThread/storeThread=true. Полный
сериализованный JSON совпал со снимком перед выгрузкой. Естественный цикл
UNLOAD → LOAD этой тестовой Fox **PASS**, подменного спавна не было.
Четыре agent-logpoints/временная attach configuration удалены, debugger отключён,
две исходные пользовательские exception-breakpoints сохранены. Сервер продолжил
работу. Java/GDD/JSON/ассеты не менялись; 0A IN_PROGRESS/D13 OPEN — другие виды,
другая роль/собственный компонент/полный rollback/принятие/права/store ещё TODO.

Следующий пакет 25.10.2026: [другой AI Role ID и собственный marker](stage-0-v07-role-marker-probe.md).
Добавлены ограниченная консольная команда, сериализуемый диагностический компонент
`genesis:compatibility_probe_v1` и технический Variant Genesis_CompatibilityFox,
наследующий Fox без Modify. Изменяющая команда включается отдельным opt-in,
ограничена явно заданными world/entity UUID, Frozen и ConsoleSender.
Первый запуск выявил необходимость валидного default для native validateDefaults;
debugger подтвердил пустые поля/schema=0. Исправлен неактивный default и добавлен
регрессионный тест реального вызова validateDefaults.
Полный прогон `build verifyPluginJar testGddCatalogValidator verifyGddCatalogs`
PASS: 348 JUnit/34 Python без ошибок/пропусков. Исправленный сервер/три конфигурации
NPC загрузились. В живом цикле Fox → Genesis_CompatibilityFox → Fox сохранены UUID,
operation и точные поля marker/Snapshot, старые Ref невалидны; после finish marker
удалён, независимый финальный dump равен исходному до begin, мир сохранён.
На момент этого пакета смена другого поведения/rig, persist/reload marker, полный AI rollback/сбой add
и принятие/ownership оставались TODO. Временная отладка убрана, сервер работает.
GDD/нормативные JSON и визуальные ассеты не менялись; добавлен только технический
role JSON. 0A IN_PROGRESS, D13 OPEN.

25.10.2026 [общий прогон](stage-0-v07-batch-probe.md) закрыл persist/reload своего
marker: один operation прошёл штатный restart и естественный EntitySection
UNLOAD/LOAD без повторного входа клиента, возврат и очистку. Независимые dump
подтвердили entered=reloaded, armed=returned, baseline=final, точное сохранение
marker/UUID. Мир сохранён. Асинхронная загрузка существующей секции и действие
объединены в callback мира, чтобы естественный unload не вклинивался между
командами; tick не ждёт future, генерации/принудительного unload нет.
Общая задача `verifyDevelopment` PASS: 349 JUnit/34 Python, упаковка/каталоги;
один новый тест проверяет точность сравнения промежуточной роли.
Временная отладка очищена, сервер работает. Другие виды/поведение/rig,
полный rollback/сбой add, ownership/принятие и store остаются TODO;
0A IN_PROGRESS, D13 OPEN. Нормативный GDD не менялся.

25.10.2026 следующий [пакет Wolf/Fox](stage-0-v07-species-probes.md): составлена
матрица 12 исходных видов с открытыми возрастом/правами/клиентскими допусками.
Для обоих новых Frozen wolf fixture серверный цикл source → alias → restart/
естественный UNLOAD-LOAD → source/очистка PASS: прежние UUID/operation/точный
marker, entered=reloaded, final=baseline. Fox этого прогона неизменна.
RoleProbeMarker/schema не менялись; закрытые профили не принимают чужие роли
и повторный UUID. Подготовка fixture отдельно opt-in, Frozen до SPAWN;
после подготовки создание выключено. 4 новых теста, полный verifyDevelopment:
353 JUnit/34 Python PASS. Мир сохранён, debugger очищен/отключён, сервер работает.
Это не принятие NPC/другой AI или rig/полный rollback/store; клиент Wolf и
игровой допуск ещё UNVERIFIED. 0A IN_PROGRESS, D13 OPEN; GDD не менялся.

25.10.2026 [чистый допуск/revalidate и read-only admission](stage-0-v07-adoption-preflight.md)
разделили origin/owner/controller и требуют явного ADOPT, точного scope,
доверенных ревизий/epoch, прав/Before* и проверенных допусков. UNKNOWN,
конфликт/чужое управление и изменение состояния/прав (включая ABA) запрещают
продолжение. 13 новых тестов, verifyDevelopment: 366 JUnit/34 Python PASS.
Серверный scoped probe отклонил Fox/оба wolf fixture; marker на Fox не изменил
результат. Независимые before/after всех трёх полностью равны, marker удалён,
мир сохранён, сервер работает без JDWP. Реальная авторизация/положительные
runtime-источники/сторонний мод/принятие не проверены; store/rollback TODO.
Формат RoleProbeMarker, GEN1 и нормативный GDD не менялись; 0A/D13 открыты.
