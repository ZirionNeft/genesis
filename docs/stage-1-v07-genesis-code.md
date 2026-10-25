# Этап 1: GEN1 для генома v2

Дата: **25.10.2026**. Ниже закреплена внутренняя payload-схема 2 нового
GEN1-транспорта. Исходный GDD задаёт оболочку и обязательные сведения;
названия полей, порядок массивов, представление зерна и область CRC уточнены
здесь как решения реализации. Исходные GDD/JSON не изменяются.

## Payload и канонизация

Оболочка: `GEN1.BASE64URL_NO_PADDING(UTF8(JCS(payload))).CRC32_HEX8`.
CRC32/ISO-HDLC считается по байтам UTF-8 JSON, без префикса/точек/Base64;
вывод — восемь **верхних** hex-цифр, включая ведущие нули. Импорт принимает
ровно каноническое представление, без пробелов/альтернативных экранирований,
padding, другого регистра CRC, лишних полей или другой сортировки массивов.
Старый прототипный `GEN1-...` не принимается.

Схема объекта закрытая, все поля обязательны:

| Поле | Тип / значение |
| --- | --- |
| `payloadVersion` | JSON integer `2`; это версия payload, отдельно от транспорта GEN1 |
| `registries` | Ровно `geneSchema`, `hybridGraph`, `expressionRules`, `morphologySet` |
| каждый registry pin | Ровно `id`, `version` (положительный int32), `sha256` (64 lowercase hex) |
| `contentCatalog` | Ровно `id`, `sha256`, включая текущий content fingerprint |
| `familyId` | Namespaced ID семейства |
| `historicalForm` | Namespaced ID исторической формы; effective форма рассчитывается из HERITAGE |
| `genes` | Объект gene ID → массив ровно из двух variant ID |
| `visualSeed` | Каноническая десятичная **строка** signed int64, без `+`, ведущих нулей и `-0` |
| `extensions` | Массив opaque-расширений, пустой массив допустим |

Каждое расширение содержит ровно `definition` (registry pin с SHA-256),
`schemaVersion` (положительный int32), `kind` (`required` / `optional_cosmetic`),
`payload` (Base64url без padding от исходных байтов; пустая строка допустима).
Байты не интерпретируются как JSON и сохраняются побайтово. У расширений
не может повторяться ID, включая другую версию того же ID. Массив расширений
сортируется по ContentId (namespace, затем path); копии аллелей — так же.

Объекты сортируются по **UTF-16 code units**, строки экранируются по
[RFC8785 §3.2](https://www.rfc-editor.org/rfc/rfc8785.html#section-3.2), UTF-8
строгий, lone surrogates и duplicate keys запрещены. Нормализация Unicode не
применяется. Область значений закрытой схемы — строки, int32, объекты и массивы;
она не содержит JSON floats/boolean/null. Все допустимые числовые поля точно
представимы в binary64 и имеют одинаковую JCS/ECMAScript десятичную запись.
Длинное визуальное зерно хранится строкой, а не числом с потерей точности.
Расширения используют opaque Base64, поэтому не расширяют числовой домен JSON.
Реализация не объявляется общим canonicalizer произвольных float JSON.

Четыре registry hashes и content hash сохраняются **как назначены источниками**.
Их проверка остаётся профилем [DesignFingerprint](stage-1-v07-runtime-config.md),
не пересчётом по JCS: смена профиля под прежними ID/версиями поменяла бы контракт.
Payload JCS и сверка ранее назначенных registry fingerprints — разные операции.
Будущие новые registry revisions требуют явного контракта fingerprint/архива.

## Границы

Blueprint содержит скрытые гены. XP, владелец, creatureId/operationId, груз,
экипировка, жизнь/смерть, квота возрождений и разрешение на создание NPC в него
не входят. Копирование строки не создаёт питомца и не доказывает происхождение.

Лимиты `maxCodeChars`, `maxPayloadBytes`, `maxGenes`, `maxExtensions` передаются
из `ServerConfig.genesisCode.limits`; второй таблицы defaults нет. Байтный
лимит относится к каноническому UTF-8 JSON, лимит длины — ко всей строке.
Parser ограничен глубиной 16 (закрытая схема использует не более пяти контейнеров).

Транспортная проверка и генетическая проверка выполняются отдельно. Транспорт
сохраняет структурно допустимые неизвестные ID/хеши и opaque payload, но не
доказывает их поддержку. Генетическое разрешение требует точного bundle,
всех генов/вариантов/семейства, исторических корней и пересчитывает потенциал.
Любое расширение пока отклоняется зависимым расчётом: trusted v2 extension API
ещё не реализован. `optional_cosmetic` не обходит default `missingExtensions=reject`.

Игровой импорт/экспорт ещё требует авторитетных permissions/owner/analysis,
проверки обязательных assets/rig и транзакций store. Прямых команд или отправки
полного blueprint клиенту этим пакетом не добавляется. After_analysis и публичный
паспорт должны проверяться серверной моделью знаний, а не полем из запроса клиента.

## Проверки

[GenesisBlueprint](../src/main/java/dev/ifelse/zirion/genesis/core/code/v2/GenesisBlueprint.java),
[GenesisCodeCodec](../src/main/java/dev/ifelse/zirion/genesis/core/code/v2/GenesisCodeCodec.java)
и [GeneticBlueprintResolver](../src/main/java/dev/ifelse/zirion/genesis/core/code/v2/GeneticBlueprintResolver.java)
реализованы в чистом Java Core без Gson/Hytale/новых зависимостей. Внутренний
PayloadJson реализует JCS для всего закрытого домена описанной схемы.

25.10.2026: `build verifyPluginJar testGddCatalogValidator verifyGddCatalogs`
BUILD SUCCESSFUL. **324 JUnit-теста выполнены**, 0 failures/errors/skipped;
34 Python-теста PASS, нормативные каталоги valid=true. Сервер/клиент не запускались.

Добавлены [13 тестов](../src/test/java/dev/ifelse/zirion/genesis/core/code/v2/GenesisCodeCodecTest.java):

- [31 независимый полный вектор](../src/test/resources/genesis-code-v2-vectors.json),
  [генератор Python](../src/test/python/v2_genesis_code_vectors.py): все 28 форм,
  полные геномы/пины, крайние signed int64 зерна, required/optional opaque bytes,
  пустой payload; точное совпадение UTF-8 JSON, Base64url и CRC32;
- три независимых JCS-вектора: UTF-16 сортировка, supplementary/BMP порядок,
  control escapes, Unicode без нормализации, вложенные объекты/массивы;
- неверные IDs/hashes всех четырёх реестров и content, семейство/исторические
  корни/неполный геном, genetic v1/неизвестные версии и лишние поля;
- неканонические ключи/пары/расширения, padding/trailing bits, CRC/префикс,
  `GEN1-...`, duplicate keys, invalid UTF-8/surrogates, truncation и глубина;
- границы всех четырёх config limits, UTF-8 bytes вместо числа символов,
  seed без потери точности, opaque bytes и duplicate extension IDs;
- 1 000 случайных malformed payload с контролируемым отказом, immutable
  snapshots и параллельные roundtrips.

Нормативные JSON, назначенные fingerprints и ассеты не менялись. Генетический
resolver не проверяет наличие игровых моделей и не возвращает разрешение
импорта/спавна. [Чистые знания/публичный паспорт/after_analysis](stage-1-v07-knowledge.md)
добавлены следующим пакетом. Поддержка trusted v2 extensions, настоящие серверные
permissions/действия, assets и store остаются открытыми задачами плана.
