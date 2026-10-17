# CF Coach — Android / Codeforces

An Android app built in Kotlin + Jetpack Compose (UI in Russian).

## Run

1. Install Android Studio (JDK 17), Android SDK 35.
2. Open the `CodeforcesCoach` folder as a project. If Android Studio requests a Gradle wrapper, install Gradle 8.9 and run `gradle wrapper --gradle-version 8.9` in the folder (the ZIP intentionally does not include a Gradle wrapper JAR).
3. Let Gradle sync. Run on an emulator/device (Android 8.0+).
4. Enter your Codeforces handle and optionally comma-separated participant handles. Tap **Обновить статистику**.
5. Permit Android notifications to receive contest/rating change alerts.

No APK is included: this is the source project, not a compiled or device-tested release.

## Features

- Public Codeforces contests (upcoming/live), last contest rating changes.
- Personal user rating and rating history plot, last 1000 submissions.
- Training task recommendations near `rating + 100`, prioritizing tags with unsuccessful recent problem attempts, excluding accepted problems from the downloaded sample.
- Peer group leaderboard by *global* Codeforces rating for manually supplied handles.
- Periodic WorkManager job checking new public contests and personal rating changes. Runs at roughly 15-minute minimum intervals, subject to OS throttling, battery and network policies; **not real-time or guaranteed**.
- Links to official problem/contest pages.

## Limitations

- Official **private Codeforces group contest standings are NOT implemented**: API endpoints for private gym/mashups require authenticated access, and the application does not ask for or store Codeforces credentials. The "Группа" tab is a manual peer ranking based on public account ratings.
- Recommendation algorithm is rule-based, **not AI/ML** and is computed only from the latest 1000 submissions.
- Initial background check takes a snapshot and does not send old-contest alerts.
- Network errors, rate limits and Android background restrictions can delay updates. Use the refresh button for manual updates.
- APIs: https://codeforces.com/apiHelp/methods

## Security & privacy

The app accesses HTTPS `codeforces.com/api` without credentials. Handle and peer list are stored locally in Android SharedPreferences. It does not upload personal data to third-party analytics services.
