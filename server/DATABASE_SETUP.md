# Подключение PostgreSQL к API в IIS

Инструкция для Windows Server 2022 и PostgreSQL на том же сервере, что и IIS.
Android 2.0.0 хранит общие задачи в отдельной базе **mobile_tasks** через API.
Сначала подготовьте роль и базу, затем настройте соединение и примените миграции.
Если база и роль уже созданы, переходите к шагу 2.

## 0. Подготовьте PostgreSQL через pgAdmin

Откройте pgAdmin **на сервере** и подключитесь к существующему экземпляру PostgreSQL под его администратором. Это пароль PostgreSQL, заданный при установке.

Если экземпляр ещё не зарегистрирован в pgAdmin:

1. **Servers → Register → Server**. В General задайте понятное имя, например `PostgreSQL сервера`.
2. Во вкладке Connection: Host=`127.0.0.1`, Port=`5432`, Maintenance database=`postgres`, Username=`postgres` (либо ваш администратор PostgreSQL).
3. Введите пароль администратора PostgreSQL и сохраните подключение. Если pgAdmin открыт на другом компьютере, `127.0.0.1` укажет на этот компьютер; настройку выполняйте в сеансе сервера.
4. В Query Tool базы postgres проверьте `SHOW port;` и `SELECT version();`. Для текущего сервера ранее подтверждены порт 5432 и PostgreSQL 14.1. Используйте фактический порт, если он изменился.

Создайте отдельную роль приложения:

1. **Login/Group Roles → Create → Login/Group Role**.
2. General → Name: `mobile_tasks_api`.
3. Definition → Password: задайте отдельный надёжный пароль и сохраните его в своём менеджере паролей. Он понадобится скрипту Configure-Database.ps1.
4. Privileges → Can login?=`Yes`. Superuser, Create roles, Create databases, Update catalog и Replication=`No`. Inherit можно оставить `Yes`.
5. Сохраните роль. Если она уже существует, используйте её; повторно создавать или менять пароль без необходимости не нужно.

Создайте базу:

1. **Databases → Create → Database**.
2. Database: `mobile_tasks`.
3. Owner: `mobile_tasks_api`.
4. Encoding: `UTF8`; прочие параметры локали оставьте совместимыми с вашим кластером, по умолчанию.
5. Сохраните. В свойствах базы проверьте имя владельца. Базы 1С для этого API не используются.

Таблицы вручную в pgAdmin создавать не требуется: их создаст команда `--migrate` из шага 4. Для размещения API и PostgreSQL на одном сервере достаточно локального подключения к 127.0.0.1:5432.

До настройки соединения создайте пул IIS `MobileTasksApi` по [IIS_SETUP.md](IIS_SETUP.md): скрипт выдаёт ему права чтения файла настроек.

## 1. Обновите сборку

1. Сохраните резервную копию текущего содержимого `D:\WebApps\MobileTasksApi`
   в другую папку, например `D:\WebApps\MobileTasksApi-backup-hosting`.
2. В IIS остановите только пул **MobileTasksApi** (не весь IIS и не пул 1С).
3. Скопируйте всё содержимое `publish` из нового архива в
   `D:\WebApps\MobileTasksApi`, заменив существующие файлы. В частности,
   добавятся `Npgsql.dll` и зависимости. Не копируйте одну DLL отдельно.
4. Оставьте существующие права и привязку `127.0.0.1:8088`.

## 2. Сохраните параметры подключения на сервере

Скопируйте `Configure-Database.ps1` из архива в отдельную папку на сервере,
например `D:\WebApps\Setup`. Не помещайте его внутрь сайта.

На СЕРВЕРЕ откройте PowerShell **от имени администратора** и выполните:

```powershell
& 'D:\WebApps\Setup\Configure-Database.ps1'
```

Скрипт запросит пароль пользователя **mobile_tasks_api из PostgreSQL**.
При вводе символы скрыты. Нужен пароль роли из pgAdmin, а не пароль Windows,
1С, Firebase или администратора postgres.

Если политика запуска скриптов блокирует выполнение, выполните однократно:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'D:\WebApps\Setup\Configure-Database.ps1'
```

Это параметр только для данного процесса, без изменения постоянной политики.
При запрете групповой политикой обратитесь к администратору.

Скрипт сохраняет настройки в **D:\WebApps\MobileTasksConfig\database.json**,
за пределами сайта. Секрет хранится в файле на сервере; доступ выдаётся
SYSTEM, администраторам и на чтение только пулу MobileTasksApi.
Файл не включён в архив, APK или GitHub. Скрипт не выводит пароль и
корректно обрабатывает кавычки, обратные слеши и точки с запятой.

Параметры: Host=127.0.0.1, Port=5432, Name=mobile_tasks,
Username=mobile_tasks_api. Если фактические параметры отличаются,
отредактируйте эти четыре значения в скрипте до запуска.

Расположение конфигурации по умолчанию: папка MobileTasksConfig рядом
с папкой сайта. Для другого размещения можно задать переменную среды
MOBILETASKS_CONFIG_FILE в настройках приложения IIS (полный путь к файлу,
без пароля). Скрипт также принимает параметр -ConfigDirectory.

## 3. Запустите и проверьте

Запустите только пул **MobileTasksApi**. После изменения настроек при
работающем API выполняйте Перезапуск/Recycle этого пула.

Откройте В БРАУЗЕРЕ НА СЕРВЕРЕ:

```text
http://127.0.0.1:8088/health/database
```

Успех (HTTP 200):

```json
{"status":"ok","database":"connected"}
```

При ошибке возвращается HTTP 503 и безопасный код, без пароля и строки
подключения:

| code | Что проверить |
|---|---|
| database_not_configured | Файл настроек не найден или обязательные поля пусты. Проверьте путь, затем перезапустите пул. |
| database_configuration_invalid | В частности, проверьте числовой порт (1–65535). |
| database_authentication_failed | Пароль роли mobile_tasks_api. Запустите скрипт повторно с верным паролем и перезапустите пул. |
| database_access_denied | Разрешения подключения PostgreSQL (pg_hba.conf). Не открывайте доступ всем адресам. |
| database_not_found | Существует ли база mobile_tasks. |
| database_permission_denied | Права роли на подключение к отдельной базе приложения. |
| database_connection_failed | Запущена ли служба PostgreSQL, верны ли адрес и порт. |
| database_timeout / database_unavailable | Доступность PostgreSQL и журнал сервера базы. |

Если вместо JSON показана ошибка запуска 500.30, проверьте журнал событий
Windows. В частности, убедитесь, что пул может читать database.json и файл
содержит корректный JSON. Не присылайте сам файл с паролем.

`/health/live` по-прежнему проверяет лишь запуск API и может вернуть `ok`,
даже когда база недоступна. Проверка `/health/database` разрешена только
для локального адреса клиента и не кэшируется.

Для локального подключения API не нужно открывать порт 5432 в интернет
или менять публикацию OData.

## 4. Создайте таблицы приложения

После успешного `/health/database` остановите пул MobileTasksApi и выполните в PowerShell на сервере:

```powershell
& 'D:\WebApps\MobileTasksApi\MobileTasks.Api.exe' --migrate
```

Ожидается `Migration completed. Schema version: 2`. Миграция создаёт отдельную схему `mobile_tasks` и таблицы users, sessions, tasks, task_events, task_links, schema_migrations. Она запускается явно, повторный запуск допустим.

Запустите пул и откройте на сервере `http://127.0.0.1:8088/health/schema`:

```json
{"status":"ok","schemaVersion":2,"requiredVersion":2}
```

Подробности обновления и резервной копии — [TASKS_SETUP.md](TASKS_SETUP.md).

## Резервное копирование

В pgAdmin выберите **mobile_tasks → Backup**, формат **Custom**, и сохраните файл в отдельной папке резервных копий. Проверьте успешное завершение операции. Делайте копию перед обновлением схемы и регулярно во время эксплуатации. Отдельно сохраните защищённую копию `D:\WebApps\MobileTasksConfig\database.json`: резервная копия PostgreSQL не содержит этот файл или пароль роли кластера.

Официальная документация: [роли pgAdmin](https://www.pgadmin.org/docs/pgadmin4/latest/role_dialog.html), [создание базы](https://www.pgadmin.org/docs/pgadmin4/latest/database_dialog.html).
