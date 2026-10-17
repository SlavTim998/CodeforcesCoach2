# Сборка APK без Android Studio

1. Создайте пустой репозиторий в github.com/new (без README).
2. Загрузите **содержимое** папки CodeforcesCoach в корень репозитория, сохраняя пути. В корне должны быть `app`, `build.gradle.kts`, `settings.gradle.kts`, а файл workflow должен лежать в `.github/workflows/build-apk.yml`.
3. Для веб-загрузки GitHub: Add file > Upload files > перетащите содержимое папки (или папку и перенесите файлы из лишнего уровня); Commit changes.
4. Откройте Actions > Build Android APK > Run workflow > Run workflow. Сборка также может начаться при push в main.
5. После зелёной отметки откройте запуск, найдите Artifacts > CF-Coach-APK, скачайте ZIP, распакуйте `app-debug.apk` и откройте его на Android.

Если Action красная, откройте failed job > Build debug APK и проверьте ошибку. Проект не тестировался успешной сборкой, поэтому гарантировать её нельзя.

Только телефон: браузер GitHub не всегда может загрузить дерево папок с сохранением путей. В этом случае используйте Codespaces (Code > Codespaces > Create codespace), загрузите ZIP из телефона в файловую панель, распакуйте в терминале `unzip CodeforcesCoach-source.zip`, затем перенесите содержимое папки CodeforcesCoach в корень репозитория, сделайте commit/push и откройте Actions. Возможность Codespaces зависит от аккаунта и квот.
