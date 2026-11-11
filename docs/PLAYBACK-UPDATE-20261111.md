# Playback and menu update

Personal 0.7.10 / commercial 0.1.2. Station/source ranking is cached during background catalog loading; sorting menu items no longer materializes a merged backup list for every duplicate channel. A 1500-source regression covers ranking and backup retention.

Buffer targets: 15–45 seconds, start 2 seconds, resume 5 seconds; allocation target 24 MiB (not a total process memory cap). Manifest live hold-back is preserved. Three actual rebuffers totalling at least 6 seconds within 60 seconds trigger the next existing alternative when available. Pauses and startup are excluded.

Repair searches up to eight matching public-catalog candidates in the personal build and seeks two successful 3-second muted video observations per channel; these are not long-term stability or audio guarantees. Commercial repair refreshes user subscriptions; no personal TV list is bundled.

Current Mac network probes of four Jinying candidates: three timed out and one HTTP 200 response was not an HLS manifest. No new candidate was promoted to a verified source. TV-side continuous playback is still required; no claim of smooth Jinying playback is made.
