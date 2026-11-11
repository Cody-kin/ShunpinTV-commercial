# Regional playback work in progress

No release recommendation: Jinying continuous playback remains unresolved.

Fixed a startup watchdog gap: READY without a rendered first frame no longer cancels the deadline. When a backup exists, a first-frame wait exceeding 15 seconds advances to it; a sole source retains the configured timeout. This does not prove the user's Jinying source is repaired.

Personal build adds an independently updatable Hong Kong subscription from https://iptv-org.github.io/iptv/countries/hk.m3u on launch and repair. Failed/empty updates retain prior entries. User deletion survives automatic refresh, while explicit repair can restore the requested regional feed. Commercial builds do not automatically import this feed.

Bay Area TV: emulator test PASS, 60 seconds, 60 playing samples, 0 rebuffer events, decoded format 1920x1080. No manual audio assessment or TCL hardware claim. Five responding HLS candidates are a separate editable personal batch, not five playback-verified sources.

Hong Kong probes: RTHK31, Golden Jade, Mei Ah and Pet Club returned HLS manifests; no continuous-play claim. Phoenix Chinese/InfoNews timed out, Jade returned 404, ViuTV and RTHK32-35 returned 403, HOY candidates returned 403. Access denials were not bypassed. The list does not currently contain every requested Hong Kong station.

Personal unit tests/build/lint and commercial build/lint passed after updating the navigation-only test fixture to exclude new auto-play seeds.
