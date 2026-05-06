# stream-to-csv

Listen to an [AudD](https://audd.io) stream-recognition slot via longpoll and append every match to a CSV (`received_at, radio_id, timestamp, score, artist, title, album, song_link`).

```
export AUDD_API_TOKEN=...        # https://dashboard.audd.io
```

**Provision-and-listen** — adds a stream slot, listens, deletes the slot on exit:

```
./gradlew run --args="--url https://example.com/stream.mp3"
./gradlew run --args="--url https://example.com/stream.mp3 --radio-id 12345"
```

If no `--radio-id` is given, the slot is provisioned at `99999`.

**Listen-only** — uses an existing slot, never adds or deletes:

```
./gradlew run --args="--radio-id 12345"
```

Output defaults to `./audd_stream_tracks.csv` (override with `--output FILE`). Files are opened in append mode — re-runs add rows; the CSV header is written only when the file is fresh.

## Callback URL handling

AudD's longpoll preflights `getCallbackUrl` because it has nothing to deliver to a server with no callback configured. The two modes handle this differently:

- **Provision-and-listen**: if no URL is set, the example sets `https://audd.tech/empty/` (any 200-OK URL satisfies the server) and prints a notice. On exit it reminds you to change it via `streams.setCallbackUrl(...)` if you actually want callbacks. If you already have a real URL configured, the example doesn't touch it.
- **Listen-only**: if no URL is set, the example refuses to start — your slot already exists, and silently setting a default URL would change configuration the slot's owner didn't ask for. Set one first via `streams.setCallbackUrl(...)`.

The SDK throws `AudDBlockedException` (with `errorCode == 19`) for the "no callback URL configured" server response — this example branches on `errorCode`, not on the exception class, so it stays correct if the SDK ever resharpens the mapping.

## Shutdown

A JVM shutdown hook (Ctrl-C) flushes the CSV, deletes the provisioned slot in mode 1, and surfaces any cleanup errors to stderr.
