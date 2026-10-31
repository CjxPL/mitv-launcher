# MiTV Launcher

Własny ekran główny dla Mi TV Stick (HD, Android 9).

- zegar i data,
- rząd **Ulubione** (przytrzymaj OK na aplikacji, żeby dodać lub usunąć),
- skróty: Ustawienia, Wi-Fi, **Aktualizuj**,
- **Wszystkie aplikacje** — telewizyjne i wgrane z telefonu.

## Jak powstaje nowa wersja

Każde wypchnięcie kodu na `main` buduje APK w GitHub Actions i publikuje wydanie.
Na telewizorze: kafelek **Aktualizuj** → pobranie → potwierdzenie instalacji.

Najnowszy plik zawsze pod adresem:
`https://github.com/CjxPL/mitv-launcher/releases/latest/download/MiTVLauncher.apk`

## Przywrócenie oryginalnego ekranu głównego

Przez ADB:

```
adb shell pm enable --user 0 com.google.android.tvlauncher
```

(Dokładną nazwę pakietu oryginalnego launchera sprawdzamy przed wyłączeniem.)
