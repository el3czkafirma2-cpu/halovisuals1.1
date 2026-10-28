# Halo Visuals (HV) — Fabric 1.21.4, client-side

Wszystkie funkcje są darmowe i odblokowane. ClickGUI: domyślnie klawisz `I` (zmiana: Client → Click GUI → Key).
Prawy klik na module = rozwiń ustawienia, lewy = włącz/wyłącz. Konfiguracja: `.minecraft/config/halovisuals.json`.

## Jak dostać gotowy .jar (bez IDE)

### Sposób A — GitHub Actions (nic nie instalujesz)
1. Załóż darmowe konto na github.com i utwórz nowe repozytorium (np. `HaloVisuals`).
2. Wgraj całą zawartość tego folderu (Add file → Upload files), razem z ukrytym folderem `.github`.
3. Zakładka **Actions** → workflow `build` → poczekaj ~2–4 min na zielony znaczek.
4. Wejdź w ten przebieg → sekcja **Artifacts** → pobierz `HaloVisuals-jar` (zip z plikiem `halovisuals-1.1.0.jar`).

### Sposób B — lokalnie
Wymagane: JDK 21 i Gradle 8.12+ (https://gradle.org/install). W folderze projektu: `gradle build`
Plik: `build/libs/halovisuals-1.1.0.jar`

## Instalacja
Zainstaluj Fabric Loader dla 1.21.4, wrzuć do `mods` plik Fabric API (1.21.4) oraz `halovisuals-1.1.0.jar`.

## Zmiany w 1.1.0
- ClickGUI skaluje się automatycznie do dowolnego GUI Scale (+ przewijanie kółkiem myszy).
- Hit/Kill Sounds: suwak „Loudness” 0.1–3.0 (domyślnie 2.0, warstwowe odtwarzanie ponad limit 1.0).
- Particles: tryby Angel Dust / Sparks / Golden Petals / Bleeding Sparks dla Hit, Jump i Ambient.
- Target HUD działa też po najechaniu celownikiem; pokazuje zbroję, item w ręce i durability.
- Halo i China Hat tylko nad Twoją postacią.
- Target ESP: obrotowe, złoto-białe anielskie okręgi.
> Po aktualizacji możesz skasować stary `config/halovisuals.json`, żeby wczytać nowe wartości domyślne.
