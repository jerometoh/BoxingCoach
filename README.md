# Boxing Coach

Native Android (Kotlin, Jetpack Compose) boxing training app. Generates a personalised
routine from your parameters and delivers spoken coaching cues over your music
(Spotify / YouTube Music) with optional audio ducking.

## How to build and install on your Pixel

1. Install **Android Studio** (free) on any computer: https://developer.android.com/studio
2. Open Android Studio → **Open** → select this `BoxingCoach` folder. Let Gradle sync
   (it downloads dependencies automatically — first sync takes a few minutes).
3. On your Pixel: Settings → About phone → tap **Build number** 7 times to enable
   Developer options, then Settings → System → Developer options → enable
   **USB debugging**.
4. Connect the Pixel via USB, accept the debugging prompt on the phone.
5. In Android Studio, press the green **Run ▶** button. The app installs and launches.

After the first install the app stays on your phone — you don't need the computer again
unless you change the code.

## Building entirely from your phone (no computer)

This project includes `.github/workflows/build.yml`, which builds a debug APK
in GitHub's cloud and attaches it to a Release every time you push. You only
need `git` on your phone to push the code — the actual compiling happens on
GitHub's servers, not your Pixel.

1. **Create a free GitHub account** (github.com, via your phone browser) if
   you don't have one.
2. **Create a new empty repository** — GitHub app or github.com → New
   repository → name it `BoxingCoach` → do **not** add a README → Create.
   Note the URL, e.g. `https://github.com/<you>/BoxingCoach.git`.
3. **Create a Personal Access Token** (needed to push from Termux, since
   password auth is disabled): github.com → Settings → Developer settings →
   Personal access tokens → Tokens (classic) → Generate new token → tick
   `repo` scope → Generate. Copy it somewhere safe — you'll paste it once.
4. **Install Termux** from F-Droid (not the Play Store version — it's
   outdated): https://f-droid.org/packages/com.termux/
5. **In Termux**, set up storage access and install git:
   ```
   termux-setup-storage
   pkg update -y && pkg install git unzip -y
   ```
6. **Unzip the project** (assuming the zip is in your phone's Downloads):
   ```
   cd ~
   unzip ~/storage/downloads/BoxingCoach.zip -d work
   cd work/BoxingCoach
   ```
7. **Push it to your new repo:**
   ```
   git init
   git branch -M main
   git remote add origin https://github.com/<you>/BoxingCoach.git
   git add .
   git commit -m "Initial commit"
   git push -u origin main
   ```
   When prompted for a password, paste the Personal Access Token (not your
   GitHub password).
8. **Wait for the build**: open the repo on github.com or the GitHub app →
   **Actions** tab → you'll see "Build APK" running (takes 3–6 minutes).
   A green check means it succeeded.
9. **Download the APK**: go to the repo's **Releases** section (right-hand
   side on github.com, or under the repo menu in the app) → open the latest
   build → download the `.apk` file directly to your phone.
10. **Install it**: open the downloaded file from your Files app or browser
    downloads. Android will prompt to allow installs from that app the first
    time — allow it, then tap Install.

Every time you push a change, a new Release with a fresh APK appears
automatically — no computer needed at any point.

## First launch

The app requests notification permission (for the workout foreground notification)
and microphone permission (only used if you enable experimental voice commands).

## Features

- **Setup**: toggle sections (warm-up / shadow / bag / core / cool-down), set rounds
  and lengths, and set **Difficulty** (combo & movement complexity) and **Intensity**
  (cardio load & pace) independently.
- **Review**: see the full routine round-by-round before starting. Regenerate the
  whole routine, one section, or a single round.
- **Workout**: full-screen timer, current cue in large text, bells and voice
  announcements, 10-second warnings, pause/skip/end controls. Runs in a foreground
  service so it keeps working with the screen locked.
- **Voice over music**: Settings → Voice → *Duck music* lowers Spotify/YT Music while
  each cue is spoken (Android transient audio focus); *Over music* just speaks on top;
  *Text only* is silent. TTS routes to the same output as your music (Bluetooth etc.).
- **Interruption handling**: a phone call taking audio focus auto-pauses the workout.
- **Stance**: orthodox/southpaw setting flips left/right wording in all cues.
- **Rest coaching**: toggle spoken tips during rest vs a plain timer.
- **History**: completed workouts logged locally (date, duration, difficulty, intensity).
- **Voice commands (experimental, off by default)**: say *pause / go / skip / repeat*.
  Recognition competes with loud music, so the large on-screen buttons remain the
  reliable control.

## Known limitations

- Cue generation is on-device and rule-based (curated combo library + escalation
  logic) — no internet needed, fully private, but not an LLM.
- YouTube Music occasionally handles ducking differently from Spotify; if ducking
  feels too subtle, Android's behaviour is controlled by the music app, not this one.
- Voice command accuracy degrades with loud music/bag noise (documented in Settings).
