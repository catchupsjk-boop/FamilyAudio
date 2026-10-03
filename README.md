# Family Audio (Android) — Stages 1 & 2

1. Android Studio → File → Open → select this folder `FamilyAudio`. Let Gradle sync
   (accept the prompt to create/use the Gradle wrapper).
2. Enable Developer options + USB debugging on your phone, connect, press Run ▶.
3. Tap "Start Audio Sharing" → Allow microphone (and notifications).
4. Speak: the bar moves (native mic capture works). Lock the phone 60s, unlock:
   the red notification stays and the bar keeps moving = foreground service works.
5. Stop via the in-app button or the notification's Stop action.

Stage 3 hook: AudioForegroundService.kt, comment "WebRTC hook".
Your existing index.html later goes to app/src/main/assets/index.html.

## No computer? Build the APK free on GitHub
Upload this whole folder (including the hidden .github folder) to a new GitHub repo.
Actions tab -> "Build APK" -> run -> download artifact "FamilyAudio-apk" -> install on phone.
