#!/bin/sh
# Кладёт патч «Мимо» в чекаут revanced-patches. GitHub-репозиторий ReVanced закрыт по DMCA (03.2026),
# живая копия — GitLab. Использование: ./apply.sh [папка]   (по умолчанию ./revanced-patches)
set -eu
here=$(cd "$(dirname "$0")" && pwd)
dest=${1:-"$here/revanced-patches"}
if [ ! -d "$dest/.git" ]; then
	git clone --depth 1 https://gitlab.com/revanced/revanced-patches.git "$dest"
fi
cp -R "$here/overlay/." "$dest/"
echo "Готово: $dest"
echo "Сборка: cd \"$dest\" && ./gradlew :patches:buildAndroid   (JDK 17, Android SDK, githubPackages-токен — см. README)"
