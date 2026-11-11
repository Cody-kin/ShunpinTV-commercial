# ShunpinTV Commercial Preview

Android TV player preparation by Cody-kin. **Not a Google Play release and not currently monetized.**

- Independent application ID: `com.harbour.tv.store`.
- No bundled TV channel playlist; import your own permitted HTTPS M3U/HLS sources.
- Channel grouping and fallback, editable subscriptions, native XMLTV/catch-up UI when a provider declares the required data.
- Settings → line self-check/repair: visible 3-second video checks and subscription refresh. These checks do not verify sound or long-term uptime.
- No advertising SDK, tracking, ad requests or broadcaster-ad replacement is enabled. `AdBreakSession` is a tested future adapter contract, not a revenue-generating implementation.

## Build

Use JDK 17 or newer, Android SDK 35, and Gradle wrapper 8.9.

```sh
./gradlew :store-app:assembleDebug :store-app:bundleRelease :core:check
```

Local native AI is disabled in this public snapshot. No signing keys, personal source lists, local playback history, device screenshots, source tokens or development evidence are included. The release bundle is unsigned until you configure your own upload signing key.

## Advertising preparation

For Android TV, evaluate a compatible video-ad platform (for example Google IMA with an eligible ad server). Standard Google Mobile Ads / AdMob Android SDK does not support Android TV. Revenue is not guaranteed: content rights, ad account acceptance, filled impressions, audience, geography and operating costs matter.

Replacing a broadcaster's own advertisements requires the appropriate content/ad-replacement permissions and reliable cue information. This build does neither. The future adapter must fail open to the live stream on ad error or missing cues and stop at the declared programme boundary. It must not infer permission or precise timing from screenshots or audio.

Before publishing: complete content/provider permissions, signing, store listing, support contact, privacy policy, Data safety, ads declaration, audience/children's-content assessment, TV navigation and real-device testing. See `PRIVACY.md` and `THIRD_PARTY.md`.

## Source terms

No new open-source license is granted for original project code by this publication alone. Third-party files retain their own license notices. Consult the repository owner before redistribution or commercial reuse.

## Home banner and GitHub shortcut

Settings can enable a clearly labelled local home-banner preview, closed with the remote. No ad network requests or tracking occur. Empty/unconfigured placements occupy no space. Full-screen playback has no banner, preserving subtitles and the picture. A compatible TV advertising provider and account are still needed for real campaigns.

The home GitHub tile and Source Manager remember a user-supplied GitHub blob/raw playlist URL, download it and present the existing import preview. A failed download preserves existing channels. Public channel playlist availability is not evidence of commercial content distribution rights; this commercial snapshot still bundles no CCTV streams. Personal builds retain their existing CCTV presets.
