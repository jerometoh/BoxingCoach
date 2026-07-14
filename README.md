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

## Faster updates: one-command push (after first-time setup)

Every zip from here on includes `push.sh` at the repo root, which does the whole
unzip → copy → commit → push sequence in one command, using whatever zip is newest
in your Downloads.

**One-time setup** (do this once, now that `push.sh` exists in the repo):
```
cd ~/BoxingCoach
echo "alias bcpush='bash ~/BoxingCoach/push.sh'" >> ~/.bashrc
source ~/.bashrc
```

**From then on**, every update is just:
1. Download the new zip from the chat (as usual).
2. In Termux, from anywhere:
   ```
   bcpush
   ```
   or with a custom commit message:
   ```
   bcpush "describe the change"
   ```

That replaces the whole unzip/copy/commit/push block you'd otherwise scroll up to
copy each time. If `bcpush` isn't found in a new Termux session, it means
`~/.bashrc` isn't being sourced automatically — run `source ~/.bashrc` once, or
just use `bash ~/BoxingCoach/push.sh` directly, which always works regardless of
aliases.

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

## Changelog

### v7 — embedded voice removed
The embedded neural voice (sherpa-onnx / Piper) crashed at native-library load on
the target device, reproducibly, across two different model sizes (medium and low)
— which pointed to the native library itself not working on that device rather than
any one model file. Diagnosing it further would have needed a device-level crash
log (adb), which couldn't be captured on-device (wireless-debug pairing kept
failing). Rather than ship a known crash, the whole embedded-voice path was removed:
the sherpa-onnx dependency, the bundled model assets, and the experimental toggle
are all gone. The app is back to the system voice, stable. The `SpeechEngine`
interface and `CoachVoice` facade remain, so a different embedded engine could be
slotted in later without disturbing the rest of the app. For a better system voice
in the meantime, installing additional Google TTS voices on the phone and picking
the deepest one in Settings remains the best available lever (see the voice notes
below).

### v6
- **Old Release APKs no longer pile up**: the build workflow now deletes every
  other release right after publishing the new one, so the Releases page always
  shows just the latest build. This runs automatically on every push — nothing
  changes in how you update the app. The next successful build also cleans out
  the existing backlog of old releases in one go.

### v5 — embedded voice (no phone-side TTS setup)
Added a fully offline, bundled neural voice using the open-source **sherpa-onnx**
library (runs Piper/VITS models via ONNX Runtime, entirely inside the app — MIT
licensed). This replaces needing to install/configure a separate TTS app or dig
through Android's system voice settings: once a model is added to the repo, it's
baked into the APK and just works for anyone who installs it.

**Update**: the first push of this failed — I'd guessed the wrong JitPack coordinates
(`sherpa-onnx-android`, a name that doesn't actually exist there). The real
coordinates, found in the project's own `jitpack.yml`, are `com.github.k2-fsa:sherpa-onnx:v1.12.40`
— note it's the repo name, not a separate Android artifact, and JitPack just
re-publishes their prebuilt `.aar` release asset rather than compiling the C++ from
scratch, which should also make this faster/more reliable than a from-source
JitPack build. Fixed in this version. If it still fails to resolve, that's the
next thing to send me the error on.

**This is the riskiest change so far** — it's a third-party native library I
couldn't compile-test in my build environment. The app is written to fail safe:
if the model files aren't present, or anything about loading them goes wrong, it
falls back to the system voice automatically (same as before) rather than
crashing. If the Actions build itself fails to *compile*, though, that's a
different kind of problem — send me the exact error from the failed step and
it's almost certainly a small API signature fix, not a redesign.

**To actually enable the embedded voice** (optional — the app works without this,
just using system TTS as before):

1. Pick a voice and download it (these are real, verified download links —
   ~15–60MB each, MIT-licensed Piper voices with the espeak-ng phonemizer data
   already bundled in):
   ```
   # Deep, energetic male options — pick one:
   wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2
   # or:
   wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2
   ```
   If a specific filename 404s (voice lineup shifts over time), browse
   https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models for the current
   list — anything named `vits-piper-en_*` will work the same way.
2. Extract and rename into place (from Termux, in the repo root):
   ```
   cd ~/BoxingCoach
   mkdir -p app/src/main/assets/piper
   tar xjf ~/storage/downloads/vits-piper-en_US-ryan-high.tar.bz2 -C ~/tmp_piper --strip-components=1
   mv ~/tmp_piper/*.onnx app/src/main/assets/piper/model.onnx
   mv ~/tmp_piper/tokens.txt app/src/main/assets/piper/tokens.txt
   mv ~/tmp_piper/espeak-ng-data app/src/main/assets/piper/espeak-ng-data
   rm -rf ~/tmp_piper ~/storage/downloads/vits-piper-*.tar.bz2
   ```
   (You may need `mkdir -p ~/tmp_piper` first if it doesn't exist, and
   `pkg install tar -y` if `tar` isn't already available in Termux.)
3. Commit and push as usual. The next build will bundle the model in the APK —
   Settings will show "Using the embedded coach voice" once it's installed, and
   the system-voice picker disappears (it's only relevant as the fallback).

Note this adds real size to the APK (tens of MB, once) and this v1 only supports
one bundled voice — no in-app picker between multiple embedded voices yet; that's
a reasonable next step if the voice quality proves worth it.

### v4
- **Rest between rounds vs. between segments, both configurable** (Setup screen):
  "Rest between rounds" (default 60s) applies between rounds within shadow boxing
  or heavy bag; a new "Rest between segments" break (default 120s) is inserted
  between warm-up → shadow → bag → core → cool-down so there's real time to put on
  or strip off gloves/wraps. As before, the next segment's round-1 instructions are
  spoken partway through that break, not counted against round time.
- **10-second warning simplified**: now just the clapper sound plus one plain
  "Ten seconds remaining" line — no more randomized hype phrasing at that moment
  (the earlier variety pool is still used for the halfway/1-minute/30-second
  callouts, just not at 10s per your feedback).
- **Real audio clips, if you want to add them**: the app now checks for
  `app/src/main/res/raw/bell.mp3` and `app/src/main/res/raw/clapper.mp3` at
  startup and plays those instead of the synthesized tones when present — no code
  changes needed on your end, just drop the files in and rebuild. I can't
  download binary audio myself (no network access in my build environment), but
  here are CC0 (public-domain-equivalent, no attribution required) sources I
  found:
  - Bell: https://bigsoundbank.com/boxing-bell-1-s1926.html (or -2, -3 on the same
    site — pick whichever ring you like)
  - Clapper/clacker: https://bigsoundbank.com/clapperboard-s1011.html or
    https://freesound.org/people/uEffects/sounds/207867/
  To add them from Termux: download the mp3 to your phone, then
  ```
  cd ~/BoxingCoach
  mkdir -p app/src/main/res/raw
  cp ~/storage/downloads/<file>.mp3 app/src/main/res/raw/bell.mp3     # rename accordingly
  cp ~/storage/downloads/<file2>.mp3 app/src/main/res/raw/clapper.mp3
  git add -A && git commit -m "add real bell/clapper clips" && git push
  ```
  Resource filenames must be lowercase letters/numbers/underscore only. Without
  these files the app falls back to the synthesized tones automatically — nothing
  breaks either way.
- **On the voice**: Android's stock TTS engines (Google, Samsung) are genuinely
  limited in expressiveness — no amount of rate/pitch tuning in-app fixes that, and
  a Terry-Crews-specific voice isn't something I can build (see v3 notes on
  consent/licensing). The most promising real option I found: **VoxSherpa**
  (open-source, on GitHub: github.com/CodeBySonu95/VoxSherpa-TTS), a free Android
  app that runs the open-source Piper and Kokoro neural TTS models fully offline
  and registers them as a normal system TTS engine. If you install it and set it
  as your phone's default TTS engine (Settings → System → Languages → Text-to-speech
  output → Preferred engine), its voices should automatically show up in this
  app's Settings → voice picker — no code change needed here, since the picker
  just lists whatever the system TTS engine offers. Worth trying; I haven't been
  able to test it myself.

### v3
- **Fixed signing key** (`keystore/debug.keystore`, committed to the repo): every
  build now has the same signature, so from this version onward new APKs install
  **over** the old one — no more uninstalling, and workout history survives updates.
  (You'll need one last uninstall/reinstall to get from the differently-signed v2
  to this one.)
- **Intros moved to rest time**: each round's combo assignment is now spoken during
  the preceding rest ("Next: Heavy bag — Round 3 of 6. Two combos. One: ...") so you
  have the picture before the bell. The first round of a section gets its intro at
  the start of the round.
- **Skip / End / app close now cut speech instantly** instead of letting queued
  lines finish. Swiping the app away also stops the workout entirely.
- **Time callouts during rounds**, phrased with variety: halfway, one minute left,
  thirty seconds, 10-second warning, and a spoken 3-2-1 at the very end of each round.
- **Real boxing sounds, synthesized in code**: the round bell is now a metallic
  "ding-ding" ring (not a beep) and the 10-second warning is the wooden triple-clack
  clapper used in boxing gyms. The clapper always fires at 10 s (when enabled);
  a spoken "ten seconds" line rides along only some of the time. Both remain
  toggleable in Settings. These are synthesized, so they're close to — not identical
  to — recorded gym sounds; swapping in real recordings is possible later if wanted.
- **Voice picker in Settings**: choose any English voice installed on the phone,
  with a spoken preview on selection. Note: named celebrity voices (e.g. Terry
  Crews) aren't possible — Android TTS only offers the generic voices installed on
  the device, and cloning a real person's voice would raise consent/licensing
  problems besides. Best available option: install more Google TTS voices on the
  phone (Android Settings → System → Languages → Text-to-speech output → install
  voice data) and pick the deepest one in the app's picker.
- **Trigger legend on the workout screen**: a compact line under the round label
  shows this round's mapping, e.g. `Go 1 → jab, cross · Go 2 → left hook, cross ·
  Down → two squats`. Trigger words themselves now vary round to round (Go/Hit/Shoot,
  Down/Drop) and each round has ONE fixed conditioning move, stated in the intro.
- **Settings layout fixed**: toggle labels no longer wrap in a way that detached a
  switch from its label (the "stray toggle"), and the screen scrolls.

### v2

- **Screen stays on during a workout** (Settings → "Keep screen on", default on) —
  only while a workout is actively showing on screen; normal battery behaviour resumes
  as soon as you leave the workout screen.
- **Better voice**: the app now picks the highest-quality on-device English voice
  available and speaks slightly faster and a touch lower — reads more like a live
  corner call than a narrator. If it's still not to your taste, the single biggest
  lever is which TTS voices are installed on the phone, not app settings — see
  "Improving the voice further" below.
- **Workout screen re-weighted**: round/section label is now a small single line at
  the top; the timer and the current instruction dominate the screen. Live commands
  ("Go", "Down", "Feint"...) render extra large and bold; the once-per-round
  explanation renders smaller since it's read, not reacted to.
- **Clap sound at 10 seconds left** and **bell at every round change** — both
  toggleable in Settings. These are synthesized tones (no audio files bundled), so
  they're simple beeps/clap-pattern rather than a recorded bell — good enough as a
  timing cue, not a sound-design centerpiece.
- **New round model — one (or two) combos per round, called out before it starts.**
  Instead of a stream of different combos to parse mid-round, each round now opens
  with a spoken/text intro like *"This round: focus on jab, cross, left hook. When I
  say go, throw it. Feint and jab to hold range in between. When I say down, hit the
  floor for a quick move."* During the round you only hear short triggers: **Go**
  (throw the assigned combo — or **Go one** / **Go two** on rounds with two combos),
  **Down — ...** (a quick conditioning move), and spacing fillers (**Feint**, **Jab,
  keep range**, **Circle left**...) so you're not standing idle between attacks. This
  applies to both shadow boxing and heavy bag rounds. Difficulty controls combo
  complexity and whether 1 or 2 combos are assigned; intensity controls how often
  "Go"/"Down" are called.

### Improving the voice further
Android's built-in TTS quality varies a lot by phone and by which voice pack is
installed. On your Pixel: Settings → System → Languages & input → Text-to-speech
output → tap the gear next to "Google Text-to-speech" (or your engine) → Install
voice data → download a higher-quality English (UK) voice if one isn't already
present. The app automatically picks the best-quality installed voice on next launch
— no in-app step needed once a better voice is on the device.

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
