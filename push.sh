#!/data/data/com.termux/files/usr/bin/bash
# Pulls in the newest BoxingCoach*.zip from Downloads, applies it over the repo,
# commits, and pushes. Run from anywhere in Termux: bash ~/BoxingCoach/push.sh
# Optional commit message: bash ~/BoxingCoach/push.sh "my message"
set -e

REPO_DIR="$HOME/BoxingCoach"
cd "$REPO_DIR"

ZIP=$(ls -t "$HOME/storage/downloads/"BoxingCoach*.zip 2>/dev/null | head -n1)
if [ -z "$ZIP" ]; then
  echo "No BoxingCoach*.zip found in Downloads — download the latest zip from the chat first."
  exit 1
fi
echo "Using: $ZIP"

rm -rf "$HOME/tmp_boxingcoach"
mkdir -p "$HOME/tmp_boxingcoach"
unzip -oq "$ZIP" -d "$HOME/tmp_boxingcoach"
cp -r "$HOME/tmp_boxingcoach/BoxingCoach/." .
rm -rf "$HOME/tmp_boxingcoach"
rm -f "$HOME/storage/downloads/"BoxingCoach*.zip

# Any APK already in Downloads is from a build that's about to be superseded —
# clear it out now so old and new installs don't pile up waiting to be sorted.
APK_COUNT=$(ls "$HOME/storage/downloads/"app-debug*.apk 2>/dev/null | wc -l)
if [ "$APK_COUNT" -gt 0 ]; then
  echo "Removing $APK_COUNT old APK(s) from Downloads (superseded by this push)."
  rm -f "$HOME/storage/downloads/"app-debug*.apk
fi

MSG="${1:-update $(date '+%Y-%m-%d %H:%M')}"
git add -A
git commit -m "$MSG" || echo "Nothing changed — nothing to commit."
git push

echo ""
echo "Pushed. Check the build: https://github.com/jerometoh/BoxingCoach/actions"
