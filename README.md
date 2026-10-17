# FileRelay

Android app that gets files out of WhatsApp fast: share a file to it and it goes straight to
Telegram (or anywhere), gets saved to `Download/FileRelay`, or opens in the built-in reader.

**Download:** [FileRelay.apk (latest)](https://github.com/Mulhimfy/filerelay/releases/latest/download/FileRelay.apk)

## What it does

- **Share targets** (in any app's share menu):
  - **Relay to Telegram**: no screens, goes straight to Telegram's chat picker.
  - **Save to Downloads**: copies the file to `Download/FileRelay` and closes. Shared links get downloaded.
  - **FileRelay**: a sheet with every option (Telegram, share, save, open, download link).
- **WhatsApp tab**: reads WhatsApp's own media folders (WhatsApp and WhatsApp Business): recent files,
  documents, images, videos, audio, voice notes, statuses, GIFs, stickers. Search, multi-select,
  one-tap send to Telegram, save, delete.
- **Files tab**: full storage browser with shortcuts (Downloads, WhatsApp, Telegram, Camera, SD card).
- **Downloads tab**: paste a link to download it; progress, retry, send the result to Telegram.
- **Reader**: PDF (pinch to zoom), images (zoom), video and audio player, text/code, ZIP contents
  (tap an entry to open it). Also listed under "Open with" for any file.
- Photos and videos go to Telegram **as files** (original quality) by default; switch it off in Settings.

## Notes

- Reading WhatsApp's folders needs "All files access" (Android 11+). Sharing and saving work without it.
- WhatsApp only puts a file on the phone after it's downloaded in the chat. Turn on
  WhatsApp → Settings → Storage and data → Media auto-download → Documents to have files ready.
- Builds run on GitHub Actions (`.github/workflows/build.yml`). Push a `v*` tag to publish a release.
