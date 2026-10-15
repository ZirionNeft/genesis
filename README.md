# Genesis

Мод для **Hytale 0.7.0**. Разработчик — **Zirion**, издатель — **if-else.dev**.

Основа — серверный Java-плагин с журналированием запуска и остановки.
В JAR также включён asset pack с простым тестовым NPC для проверки моделей.
Остальные игровые механики будут добавлены после описания требований.

## Сборка

Нужен **JDK 25**. Используйте включённый Gradle Wrapper 9.6.0:

```sh
./gradlew clean build
```

Результат: `build/libs/Genesis-0.1.0-SNAPSHOT.jar`. Проверка `verifyPluginJar`,
включённая в `build`, проверяет упакованный манифест, наличие класса входа,
ресурсы NPC, анимацию, переводы и отсутствие классов сервера и исходников
Blockbench в моде. Автоматизированных игровых тестов пока нет.

Версии мода и API закреплены в `gradle.properties`. Серверный API загружается
из [официального Maven-репозитория](https://maven.hytale.com/release/com/hypixel/hytale/Server/maven-metadata.xml)
как `compileOnly` и не попадает в JAR мода.

В IntelliJ IDEA выберите JDK 25 для Project SDK и Gradle JVM, затем обновите
Gradle-проект. Для текущего macOS-окружения подходит JBR из установленной IDEA:

```sh
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew build
```

## Локальный сервер

Получите `Assets.zip` **версии 0.7.0** из обновлённого Hytale Launcher или
официального Downloader. Серверный JAR 0.7.0 Gradle загрузит самостоятельно.
Порядок получения файлов описан в
[официальном Server Manual](https://support.hytale.com/hc/en-us/articles/45326769420827-Hytale-Server-Manual).

```sh
./gradlew runServer -PhytaleAssets="/absolute/path/to/Assets.zip" --console=plain
```

Задача собирает мод, помещает его в `run/mods/Genesis.jar` и запускает сервер
из `run/`, слушая `127.0.0.1:5520` по UDP. Отправка ошибок в Sentry отключена.
Native access для серверных библиотек включён явно.
Конфигурация, логи и сохранения остаются в `run/`, который исключён из Git.
Для авторизации в консоли сервера выполните `/auth login device`.
Подключайтесь клиентом с той же версией игры; остановка сервера — `/stop`.

Для отладки:

```sh
./gradlew runServer -PhytaleAssets="/absolute/path/to/Assets.zip" --debug-jvm --console=plain
```

JVM ожидает подключения отладчика на порту `5005`; в IDEA используйте
Remote JVM Debug. `./gradlew stagePlugin` только подготавливает JAR в `run/mods/`.

Для установки на отдельный сервер скопируйте JAR из `build/libs/` в его `mods/`
и перезапустите сервер. При замене удалите прежний JAR Genesis, чтобы избежать
двух версий одного плагина. На Windows используйте `gradlew.bat`.

Проверено 13.10.2026: сервер 0.7.0 с обновлёнными ассетами загрузил Genesis,
подготовил мир и штатно остановился. Также проверены авторизация сервера
и успешное подключение игрового клиента к миру `default`.

## Модели

Каждый самостоятельный объект хранится в отдельном проекте Blockbench
в `art/models/<Name>/Genesis_<Name>.bbmodel`. Детали одного персонажа или предмета
с общей иерархией и анимациями остаются в одном проекте.
Экспортированные ресурсы размещаются в `src/main/resources/Common/`.
Порядок работы и договорённость о путях: [art/models/README.md](art/models/README.md).

## Тестовый NPC

`Genesis_TestNPC` — мирный манекен в бирюзовой рубашке с золотым знаком.
Он стоит на месте, слегка покачивается и поворачивает голову в анимации Idle.
Для демонстрации включены неуязвимость и отсутствие отбрасывания.
Естественный спавн, бой, торговля и награды для него не настроены.

1. Запустите сервер командой из раздела «Локальный сервер» и подключитесь
   клиентом Hytale **0.7.0** по адресу `127.0.0.1:5520`.
2. Для призыва нужны права оператора. В консоли сервера выполните
   `/op add <имя игрока>`, когда игрок подключён. На уже настроенном dev-сервере
   оператору повторно выдавать права не нужно.
3. В игровом чате выполните:

   ```text
   /npc spawn Genesis_TestNPC
   ```

   Это ID NPC-роли; команда создаёт NPC рядом с игроком. Выполняйте её
   на свободной ровной площадке. Для просмотра вариантов команды доступно
   `/npc spawn Genesis_TestNPC --help`. Чтобы NPC появился лицом к игроку,
   используйте `/npc spawn Genesis_TestNPC --facingRotation`.
4. Проверьте лицо и одежду, Idle-анимацию, масштаб и столкновения.
   Сам вызов NPC проверяйте из игрового чата: штатная команда требует игрока
   в мире, даже если указать координаты из консоли сервера.

Исходник: [Genesis_TestNPC.bbmodel](art/models/TestNPC/Genesis_TestNPC.bbmodel).
Превью: [preview.png](art/models/TestNPC/preview.png).
Модельный ассет — `Server/Models/Genesis/Genesis_TestNPC.json`,
NPC-роль — `Server/NPC/Roles/Genesis/Genesis_TestNPC.json`.
Ссылки в ModelAsset задаются относительно `Common/`; `Appearance` в роли
содержит ID ModelAsset. Регистрация этих ассетов через Java не требуется.

Основа рекомендаций: [официальное руководство по моделям Hytale](https://hytale.com/news/2025/12/an-introduction-to-making-models-for-hytale),
[Hytale Models для Blockbench](https://github.com/JannisX11/blockbench-plugins/blob/master/plugins/hytale_plugin/about.md)
и [ModelAsset для Release 0.7.0](https://docs.hytale.com/assets/models/).

Проверено 13.10.2026: `clean build` и `verifyPluginJar` прошли успешно,
сервер 0.7.0 загрузил Genesis asset pack, проверил новую Generic NPC-роль
и восстановил авторизацию. В Blockbench проверены модель и превью; отдельно
проверены все 60 UV-граней и соответствие анимационных целей узлам модели.
Внешний вид и анимация на игровом клиенте пока требуют ручной проверки.

Исследованные источники, особенности API 0.7.0 и правила разработки:
[AGENTS.md](AGENTS.md).
