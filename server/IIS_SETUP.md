# Установка MobileTasks.Api в IIS и настройка HTTPS

Для Windows Server 2022 Datacenter. API и PostgreSQL работают на одном сервере. Приложение Android обращается к API, а API — к отдельной базе mobile_tasks. Адреса, имена и пути ниже соответствуют текущей установке:

| Что | Значение |
|---|---|
| Сайт и пул IIS | MobileTasksApi |
| Файлы сайта | D:\WebApps\MobileTasksApi |
| Конфигурация PostgreSQL | D:\WebApps\MobileTasksConfig\database.json |
| Скрипты настройки | D:\WebApps\Setup |
| Локальная проверка | http://127.0.0.1:8088/health/live |
| Адрес приложения | https://alf-mobile-tasks.duckdns.org |
| Внешний IPv4 | 77.235.29.212 |
| Адрес в офисе | 10.10.10.254 |

Если сайт, HTTPS и соединение с базой уже настроены, используйте [обновление API](TASKS_SETUP.md). Повторно создавать сайт и базу не нужно. При установке на другом сервере замените адреса своими; URL приложения задаётся в `app/src/main/java/kg/devtasks/app/core/SharedApi.kt`.

## 1. Установите IIS и Hosting Bundle

1. Откройте **Диспетчер серверов → Добавить роли и компоненты**.
2. Выберите роль **Веб-сервер (IIS)** и средства управления IIS, включая консоль управления. Для существующей установки проверьте, что они уже включены.
3. Установите **.NET 10 Hosting Bundle для Windows x64** с [официальной страницы .NET 10](https://dotnet.microsoft.com/en-us/download/dotnet/10.0). На сервере требуется Hosting Bundle; SDK нужен только компьютеру, на котором собирается API.
4. Если Hosting Bundle был установлен до IIS, повторно запустите установщик и выполните Repair/Восстановить. Завершите перезапуск, если его требует установщик, в согласованное время обслуживания сервера.
5. В IIS выберите сервер → **Модули**. В списке должен быть `AspNetCoreModuleV2`.

## 2. Создайте пул приложения

В IIS → **Пулы приложений → Добавить пул приложений**:

- Имя: `MobileTasksApi`.
- Версия CLR .NET: **Без управляемого кода / No Managed Code**.
- Режим конвейера: **Integrated**.

В дополнительных параметрах пула:

- Enable 32-Bit Applications: `False`.
- Identity: `ApplicationPoolIdentity`.

## 3. Подготовьте файлы сайта

Если собираете API из исходников, выполните на компьютере разработчика из корня репозитория:

```powershell
dotnet publish .\server\MobileTasks.Api\MobileTasks.Api.csproj -c Release -o .\server\publish
```

На сервере создайте папки `D:\WebApps\MobileTasksApi` и `D:\WebApps\Setup`.
Скопируйте всё содержимое publish в папку сайта. Итоговый вид:

```text
D:\WebApps\MobileTasksApi\
  MobileTasks.Api.exe
  MobileTasks.Api.dll
  MobileTasks.Api.deps.json
  MobileTasks.Api.runtimeconfig.json
  Npgsql.dll
  web.config
  ... остальные опубликованные файлы
```

При использовании готового ZIP берите содержимое его папки publish. Файл web.config создаётся при `dotnet publish`; копировать только исходники .cs на сервер недостаточно.

Скрипт Configure-Database.ps1 положите в `D:\WebApps\Setup`. Документацию .md храните отдельно от сайта. Файл с паролем базы будет расположен в соседней папке MobileTasksConfig.

## 4. Выдайте права папке сайта

Для **D:\WebApps\MobileTasksApi** откройте Свойства → Безопасность → Изменить → Добавить:

1. Размещение: локальный сервер.
2. Введите `IIS AppPool\MobileTasksApi` → Проверить имена. Отображение сокращённого имени `MobileTasksApi` после проверки нормально.
3. Разрешите **Чтение и выполнение**, **Список содержимого папки**, **Чтение**.

Можно выполнить в PowerShell от администратора:

```powershell
icacls.exe 'D:\WebApps\MobileTasksApi' /grant 'IIS AppPool\MobileTasksApi:(OI)(CI)RX'
```

Права относятся к папке MobileTasksApi. Права конфигурации с паролем настраивает Configure-Database.ps1 отдельно.

## 5. Создайте сайт и настройте аутентификацию IIS

IIS → **Сайты → Добавить веб-сайт**:

- Имя: `MobileTasksApi`.
- Пул: `MobileTasksApi`.
- Физический путь: `D:\WebApps\MobileTasksApi`.
- Привязка: `http`, IP `127.0.0.1`, порт `8088`, имя узла пустое.

Эта привязка нужна для проверки на самом сервере. Порт должен быть свободен.

Выберите **именно сайт MobileTasksApi → Проверка подлинности**:

1. Анонимная проверка подлинности → Включить.
2. Анонимная проверка подлинности → Изменить → **Удостоверение пула приложений**.
3. Windows/Basic Authentication для этого сайта оставьте выключенными. Учётные записи приложения проверяет API по email и паролю; после входа используется Bearer-токен.

Запустите пул и сайт. На сервере откройте:

```text
http://127.0.0.1:8088/health/live
```

Ожидаемый ответ:

```json
{"status":"ok","service":"MobileTasks.Api","stage":"tasks-api"}
```

Публикация OData имеет отдельные настройки. Для настройки этого API используйте его собственный сайт и пул.

## 6. Подготовьте PostgreSQL и таблицы

Выполните [DATABASE_SETUP.md](DATABASE_SETUP.md):

1. В pgAdmin создайте роль mobile_tasks_api и отдельную базу mobile_tasks с этим владельцем.
2. Запустите Configure-Database.ps1 на сервере от администратора, введите пароль роли PostgreSQL.
3. Перезапустите только пул MobileTasksApi и проверьте локальный `/health/database`.
4. Остановите пул и выполните:

```powershell
& 'D:\WebApps\MobileTasksApi\MobileTasks.Api.exe' --migrate
```

5. Дождитесь `Migration completed. Schema version: 3`, запустите пул.
6. Проверьте `http://127.0.0.1:8088/health/schema`:

```json
{"status":"ok","schemaVersion":3,"requiredVersion":3}
```

Настройки PostgreSQL считываются из `D:\WebApps\MobileTasksConfig\database.json`. Пароль в GitHub и APK не включается. При уже работающем сервере файл конфигурации сохраняйте прежним.

## 7. Привяжите домен и внешний HTTP

Для текущего адреса используется бесплатный поддомен **alf-mobile-tasks.duckdns.org**.

1. В DuckDNS укажите внешний IPv4 `77.235.29.212`. Сообщение о том, что IP уже такой же, означает, что менять его не требуется.
2. В IIS → MobileTasksApi → Привязки добавьте: тип `http`, IP **Все неназначенные / ***, порт `80`, имя узла `alf-mobile-tasks.duckdns.org`.
3. В брандмауэре и на маршрутизаторе обеспечьте доступ снаружи к TCP 80 на этот сервер. Если есть NAT, направьте внешний TCP 80 на TCP 80 внутреннего адреса сервера.
4. С телефона через мобильный интернет проверьте **http://alf-mobile-tasks.duckdns.org/health/live**, явно указав http на этом этапе.

Регистрация и вход с внешних адресов требуют HTTPS. Проверки `/health/database` и `/health/schema` доступны с локального адреса клиента; с телефона проверяйте `/health/live`.

## 8. Выпустите сертификат и включите HTTPS

В текущей установке используется бесплатный сертификат Let's Encrypt и клиент [win-acme](https://www.win-acme.com/).

1. Распакуйте Windows x64 win-acme в постоянную папку, например `D:\Tools\win-acme`.
2. Запустите wacs.exe от имени администратора.
3. Выберите создание сертификата с обычными настройками (`N` в установленной версии).
4. Выберите сайт MobileTasksApi, затем привязку `alf-mobile-tasks.duckdns.org`.
5. Прочитайте и примите условия центра сертификации, укажите email для уведомлений, если программа его запросит.
6. Дождитесь успешной проверки домена и установки сертификата в IIS. Для используемой HTTP-01 проверки внешний TCP 80 должен быть доступен.
7. Проверьте появление HTTPS-привязки: тип `https`, IP `*`, порт `443`, имя узла `alf-mobile-tasks.duckdns.org`, нужный сертификат. Для сайтов, использующих один IP, используйте SNI по имени узла.
8. Обеспечьте доступ к TCP 443 в брандмауэре и NAT: внешний TCP 443 → TCP 443 адреса сервера в офисе.
9. В Планировщике заданий проверьте задание продления win-acme и успешность его выполнения. Папка win-acme должна оставаться на месте; для автоматического HTTP-01 продления сохраните внешний доступ к TCP 80.

Проверьте с телефона через мобильный интернет:

```text
https://alf-mobile-tasks.duckdns.org/health/live
```

Ожидаются корректный HTTPS-сертификат и JSON со status=ok. В приложении используется это доменное имя.

## 9. Проверьте доступ из офиса

Если с мобильного интернета HTTPS работает, а из офиса обращение к внешнему IP не проходит, настройте на офисном DNS запись:

```text
alf-mobile-tasks.duckdns.org → 10.10.10.254
```

Это split DNS: снаружи имя указывает на внешний IP, в офисе — на внутренний. Телефоны должны использовать этот DNS. Альтернатива — NAT loopback на маршрутизаторе, если он поддерживается. Адрес приложения остаётся `https://alf-mobile-tasks.duckdns.org`; при обращении по IP сертификат домена не совпадёт.

Диагностика из PowerShell на офисном компьютере:

```powershell
Resolve-DnsName alf-mobile-tasks.duckdns.org
Test-NetConnection alf-mobile-tasks.duckdns.org -Port 443
Test-NetConnection 10.10.10.254 -Port 443
```

## 10. Проверьте приложение

Установите APK, зарегистрируйте аккаунт с email и паролем от 12 символов. Создайте общую задачу, добавьте материал или контакт. На втором телефоне войдите другим аккаунтом, возьмите задачу и завершите её. На первом телефоне исполнитель и статус обновятся в течение 15 секунд при открытом приложении. Затем проверьте просмотр сохранённого списка без интернета.

## Ошибки и проверка

| Ошибка | Проверка |
|---|---|
| Нет AspNetCoreModuleV2 | Установка/Repair Hosting Bundle после установки IIS. |
| HTTP 500.19 | Hosting Bundle, корректный опубликованный web.config. |
| HTTP 500.31 | Наличие ASP.NET Core 10 Runtime из Hosting Bundle. |
| HTTP 500.30 | Журналы Windows → Приложение, ошибки .NET Runtime / IIS AspNetCore Module. |
| HTTP 401.3 | Права чтения папки сайта для IIS AppPool\MobileTasksApi. |
| API возвращает 401 | Вход в аккаунт приложения, действующий Bearer-сеанс. |
| database_authentication_failed | Пароль роли PostgreSQL в конфигурации; после изменения перезапустите пул. |
| schema_not_ready | Запуск --migrate, затем локальная проверка /health/schema. |
| ERR_CONNECTION_REFUSED по HTTPS | Привязка 443, брандмауэр, проброс порта и верный адрес сервера. |
| Из офиса нет связи, с мобильного интернета есть | Split DNS или NAT loopback. |

Для локальной проверки HTTPS на самом сервере с сохранением проверки доменного имени:

```powershell
curl.exe --noproxy "*" --resolve "alf-mobile-tasks.duckdns.org:443:127.0.0.1" -i "https://alf-mobile-tasks.duckdns.org/health/live"
```

Если Windows curl сообщает именно CRYPT_E_REVOCATION_OFFLINE, повторите диагностический запрос с `--ssl-revoke-best-effort`. Этот параметр относится к проверке отзыва в curl; в Android-проекте проверка TLS не отключается.

Официальные материалы: [ASP.NET Core в IIS](https://learn.microsoft.com/aspnet/core/host-and-deploy/iis/?view=aspnetcore-10.0), [win-acme](https://www.win-acme.com/manual/getting-started).
