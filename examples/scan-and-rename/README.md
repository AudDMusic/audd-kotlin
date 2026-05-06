# scan-and-rename

Walk a folder of audio files, recognize each with [AudD](https://audd.io), write `Artist`/`Title`/`Album`/`Year` tags via [jaudiotagger](https://www.jthink.net/jaudiotagger/), then rename each file to `Artist - Title.ext`.

```
export AUDD_API_TOKEN=...        # https://dashboard.audd.io
./gradlew run --args="/path/to/music"                 # dry-run (default)
./gradlew run --args="/path/to/music --apply"         # write tags + rename
./gradlew run --args="/path/to/music --apply --concurrency 8"
```

Recursively scans `.mp3 .flac .ogg .opus .m4a .mp4 .wav .aac`. Concurrency defaults to 4 — bound by a `kotlinx.coroutines.sync.Semaphore` so the AudD API isn't hit faster than the limit. Filenames with `/ \ : * ? " < > |` are sanitized to `_`; rename is skipped when the target already exists.

**`--apply` is destructive.** It writes ID3/Vorbis/MP4 tags and renames in place. Try a dry-run first and back up anything irreplaceable.

**Licensing:** `jaudiotagger` is LGPL-2.1. The AudD SDK itself is MIT — only this example pulls in the LGPL dependency, which is why each example lives in its own Gradle project rather than the SDK module.
