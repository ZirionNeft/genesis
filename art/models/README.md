# Исходники моделей Genesis

Каждый самостоятельный предмет или персонаж хранится в отдельном проекте
Blockbench. Детали одного объекта с общей иерархией и анимациями остаются вместе.
Несколько проектов можно открыть в отдельных вкладках Blockbench.

## Структура

Для каждой модели создавайте каталог с её именем:

```text
art/models/<Name>/
  Genesis_<Name>.bbmodel
```

`<Name>` — имя выбранного объекта, а не буквальное имя каталога.
Здесь же можно хранить необходимые исходники текстур и референсы.
Каталог `art/` находится вне `src/main/resources/` и не включается в JAR
стандартной сборкой Gradle. Исходники `.bbmodel` сохраняйте в Git.

Для экспорта используйте следующую договорённость о путях:

```text
src/main/resources/Common/NPC/Genesis/<Name>/
  Genesis_<Name>.blockymodel
  Genesis_<Name>.png
  Animations/
    Genesis_<Name>_<Animation>.blockyanim
```

Добавляйте только необходимые файлы: анимации нужны не каждой модели.
Это структура NPC Genesis; для других типов ресурсов проверяйте допустимые
каталоги по codec соответствующего ассета. В частности, `Common/Models/`
не входит в допустимые каталоги поля `Model` у NPC ModelAsset на 0.7.0.
Ссылки на модель, текстуру и анимации в игровых
JSON-ассетах должны соответствовать фактическим путям относительно `Common/`.
Игровые определения размещайте в `src/main/resources/Server/` по схеме
конкретного типа ассета после появления требований к нему.

## Работа в Blockbench

1. Перед работой через MCP вызовите `get_capabilities` и проверьте активный проект.
2. Выберите `hytale_character` для NPC и attachments (косметика, оружие,
   инструменты) либо `hytale_prop` для блоков и декораций. Создайте отдельный
   проект с именем `Genesis_<Name>`.
3. После создания проекта или смены режима обновите список MCP-инструментов.
   Используйте режимы Edit, Paint и Animate по необходимости.
4. Сохраняйте редактируемый проект как `.bbmodel` в его каталоге внутри `art/models/`.
5. Перед экспортом проверьте модель средствами Hytale Models; через MCP
   доступны `hytale_validate_model` и `list_export_formats`, когда выполнены
   условия редактора. Экспортируйте модель, текстуру и необходимые анимации
   в `Common/` по указанной структуре.
6. При добавлении первых игровых ассетов установите `IncludesAssetPack: true`
   в `src/main/resources/manifest.json`, выполните `./gradlew clean build`
   и проверьте загрузку и внешний вид на Hytale 0.7.0.

Тестовый NPC уже создан: [TestNPC/Genesis_TestNPC.bbmodel](TestNPC/Genesis_TestNPC.bbmodel),
[превью](TestNPC/preview.png). Он использует 10 кубов / 13 экспортированных узлов,
одну текстуру 256×256 и анимацию Idle длиной 3 секунды. Его высота — 96 единиц
Blockbench, то есть 1,5 метра в формате `hytale_character`.
Игровые ресурсы размещены в `Common/NPC/Genesis/TestNPC/`,
`IncludesAssetPack` включён. Инструкция призыва: [README.md](../../README.md#тестовый-npc).

Включайте asset pack при добавлении экспортированных ресурсов в мод,
а не при создании исходного `.bbmodel`.

Справка: [Hytale Models для Blockbench](https://github.com/JannisX11/blockbench-plugins/blob/master/plugins/hytale_plugin/about.md)
и [формат проекта .bbmodel](https://blockbench.net/wiki/docs/bbmodel/).
