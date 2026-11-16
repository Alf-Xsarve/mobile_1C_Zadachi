# Firebase: подключение и проверка

Проект: `mobile-tasks-in-1c`. Android package: `kg.devtasks.app`.

## Включение в Firebase Console

1. Откройте [проект Firebase](https://console.firebase.google.com/project/mobile-tasks-in-1c/overview).
2. В Authentication → Sign-in method включите **Email/Password**. Вход по ссылке для этой версии не нужен.
3. В Cloud Firestore создайте базу **Standard**, идентификатор **`(default)`**, выберите регион и **Production mode**.
4. Откройте вкладку **Rules**, замените весь текст содержимым [firebase/firestore.rules](firebase/firestore.rules) и нажмите **Publish**.
5. В настройках Android-приложения скачайте `google-services.json` и сохраните его в `app/google-services.json` перед сборкой. Этот файл не коммитится.

Файл `google-services.json` содержит настройки Android SDK. Он не предоставляет административный доступ для включения сервисов или публикации правил.

Альтернатива публикации правил через Firebase CLI (войти должен владелец проекта):

```powershell
cd firebase
npm ci
npx firebase login
npx firebase deploy --only firestore:rules --project mobile-tasks-in-1c
```

Сервисы Authentication и Firestore нужно включить до проверки приложения. Production mode закрывает данные до публикации правил; режим открытого доступа не требуется.

## Что доступно пользователю

- Новая регистрация по email и паролю создаёт аккаунт Firebase. После входа доступна кнопка «Общие задачи».
- Пользователь создаёт задачу с названием и описанием. Её видят все зарегистрированные пользователи этого проекта.
- Свободную задачу можно взять себе. Автор и имя исполнителя отображаются в списке и карточке.
- Завершить задачу может только назначенный исполнитель. Чужую задачу нельзя перехватить.
- Изменения появляются у остальных через подписку Firestore, без ручного обновления.
- В списке доступны «Все», «Свободно», «Мои», «Готово» и поиск по загруженным данным. Список загружается по 100 задач; «Загрузить ещё» расширяет его.
- При потере связи можно читать уже закешированные данные. Создание, взятие и завершение требуют соединения и подтверждения сервера.
- Редактирование, удаление и переназначение общих задач в этой версии не предусмотрены.

Вход в старый локальный профиль остаётся доступен. Из него можно отдельно войти в Firebase через «Общие задачи». Выход через «Аккаунт» в общих задачах отключает Firebase; выход из профиля в настройках завершает оба входа. Подключения 1С не отправляются в облако.

## Структура данных

`users/{uid}`: `name`, `createdAt`. UID берётся из Firebase Authentication. Имя в профиле неизменяемое в этой версии.

`tasks/{taskId}`:

| Поле | Значение |
| --- | --- |
| `title`, `description` | Название (3–160 символов) и описание (до 10 000) |
| `status` | `new`, `active`, `done` |
| `authorId`, `authorName` | UID и имя автора |
| `assigneeId`, `assigneeName` | UID и имя исполнителя; пустые у новой задачи |
| `createdAt`, `updatedAt` | Серверные timestamps |

Правила проверяют авторизацию, допустимые поля, размеры текста, серверное время и разрешённые переходы. Имена автора и исполнителя сверяются с профилем соответствующего пользователя. Одновременное взятие защищено транзакцией; после повторного чтения второй клиент получает сообщение о занятой задаче.

## Локальные проверки

Тестовые проект и аккаунты отдельные: **`demo-devtasks`**. В рабочий `mobile-tasks-in-1c` тесты ничего не записывают.

```powershell
cd firebase
npm ci
npm test
```

Для сценария с двумя настоящими Android SDK-клиентами запустите Firebase Emulator в отдельном терминале:

```powershell
cd firebase
npx firebase emulators:start --only auth,firestore --project demo-devtasks
```

Затем из корня проекта при запущенном Android Emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.firebaseEmulators=true
```

Android обращается к локальным серверам через `10.0.2.2:9099` и `10.0.2.2:8080`. Без параметра `firebaseEmulators=true` сценарий Firebase пропускается; проверки 1С и QR работают без Firebase Emulator.

Для Firebase BoM закреплена версия 34.7.0, совместимая со встроенным Kotlin в AGP 9.1. Firebase BoM 35.2.0 требует более новой версии Kotlin; проверку совместимости нельзя обходить отключением проверки метаданных.

Справка: [подключение Android](https://firebase.google.com/docs/android/setup), [Authentication](https://firebase.google.com/docs/auth/android/start), [обновления Firestore](https://firebase.google.com/docs/firestore/query-data/listen), [транзакции](https://firebase.google.com/docs/firestore/manage-data/transactions).
