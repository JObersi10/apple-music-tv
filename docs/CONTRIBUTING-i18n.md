# Translating Apple Music TV

The app can show its interface in any language, picked automatically from the device's language with
English as the fallback. Adding a language is a copy-and-translate job — no code, no build tools needed
beyond opening a text editor and sending a pull request.

## How it works

All user-facing UI text lives in Android string resources:

- `android/app/src/main/res/values/strings.xml` — **English**, the base. Every key lives here.
- `android/app/src/main/res/values-<lang>/strings.xml` — a translation, where `<lang>` is an ISO-639-1
  code (`nl` Dutch, `es` Spanish, `fr` French, `de` German, `ja` Japanese, `pt-rBR` Brazilian
  Portuguese, …). See `values-nl/` for a worked example.

Android loads the file matching the device language and falls back to `values/` (English) for any key a
translation is missing — so a partial translation is fine and never breaks the app.

## Add a language

1. Copy `res/values/strings.xml` to `res/values/<lang>/strings.xml` (e.g. `res/values-fr/strings.xml`).
2. Translate **only the text between the tags**. Never change a `name="…"` key.
   ```xml
   <string name="tab_library">Bibliothèque</string>   <!-- ok: key kept, text translated -->
   ```
3. Leave out any key you're unsure of — it falls back to English.
4. Open a pull request. That's it.

## Notes

- Keep placeholders (`%1$s`, `%d`) and their order intact; they're filled in at runtime.
- Don't translate brand names ("Apple Music", "Spicy Lyrics", "Shuffle" is commonly left as-is).
- The lyrics themselves are never translated here — that's the in-app lyric translation feature. This is
  only the app's own buttons, labels and messages.

## For developers

String migration is incremental: not every hardcoded string has been moved into `strings.xml` yet. When
you touch a screen, pull its user-facing strings into `strings.xml` and reference them with
`stringResource(R.string.key)` (Compose) instead of a literal. New user-facing strings should always go
into `strings.xml`, never be hardcoded. The exit dialog (`ui/AppShell.kt`) is the reference pattern.
