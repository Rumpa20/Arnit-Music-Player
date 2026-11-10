# Online Music Hub notes

## Current provider

The v2.0.0 implementation integrates Audius discovery and streaming.

- Search endpoint: `GET https://api.audius.co/v1/tracks/search`
- Stream endpoint: `GET https://api.audius.co/v1/tracks/{track_id}/stream`
- Required application identification parameter: `app_name=ArnitMusicPlayer`

The client only makes requests after the user initiates a search or playback. Search results are parsed from the provider response. It does not manufacture tracks or stream URLs.

## Catalog and licensing limitations

Audius is an independent-music catalog, not a universal commercial music catalog. Search results may vary by region, API availability, creator settings, and network connectivity. A stream being playable does not grant permission to download, redistribute, or commercially reuse it. Check each track's own terms/license before any reuse.

Genre search is based on the provider's genre metadata. Language search is keyword-based because the provider does not guarantee a normalized language field for every track.

## Future provider integrations

Other providers should be added only after confirming current API access rules, attribution requirements, streaming rules, rate limits, and per-track rights. Do not scrape consumer sites or bypass DRM/paywalls.
