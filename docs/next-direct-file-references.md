# Direct Strapi-file references in the Next.js app

Audit of `ita-nlpcc-pcc-next-app` for media files referenced **directly** (hardcoded URL / hash /
path) rather than through Strapi content relations. These are the only assets at risk if media
**binaries** are ever deleted (e.g. by Media cleanup with binary deletion enabled), because they
bypass the `files_related_mph` relations that the deduplication repoints.

_Audited: 2026-06-23 · Next 16.2.7 · scope: `app/`, `utils/`, `public/`._

## How content media is normally used (safe)

Content images are rendered by reading `.url` from the populated media object returned by Strapi —
e.g. `content.image.url`, `emergencyNumber.icon.url`, `cmsProfilePageData.inboxIcon?.url`,
`content.body.image?.url`. The frontend **never** rebuilds a URL from `hash`/`id`. When dedup
repoints a relation to the canonical file, the API simply returns the canonical's `.url` (a
byte-identical binary for CERTAIN groups). These references are therefore **safe** and are not listed
below.

## Hardcoded Strapi-CDN files (at risk if binaries are deleted)

All in `app/[locale]/(public)/login-dhp/page.tsx`, host `int-cdn.axa.it` (integration CDN),
path prefix `NLPCC_STRAPI_PCC/`. They are footer social icons.

| # | File (hash name) | Full URL | Referenced at | Notes |
|---|------------------|----------|---------------|-------|
| 1 | `icon_social_facebook_08ddb8ef70.svg` | https://int-cdn.axa.it/NLPCC_STRAPI_PCC/icon_social_facebook_08ddb8ef70.svg | `app/[locale]/(public)/login-dhp/page.tsx:151` | social icon, hardcoded |
| 2 | `icon_social_linkedin_ab395dd5ee.svg` | https://int-cdn.axa.it/NLPCC_STRAPI_PCC/icon_social_linkedin_ab395dd5ee.svg | `app/[locale]/(public)/login-dhp/page.tsx:171` | social icon, hardcoded |
| 3 | `icon_social_youtube_27fdd9936b.svg` | https://int-cdn.axa.it/NLPCC_STRAPI_PCC/icon_social_youtube_27fdd9936b.svg | `app/[locale]/(public)/login-dhp/page.tsx:191` | social icon, hardcoded |
| 4 | `icon_social_instagram_a99a73d678.svg` | https://int-cdn.axa.it/NLPCC_STRAPI_PCC/icon_social_instagram_a99a73d678.svg | `app/[locale]/(public)/login-dhp/page.tsx:211` | social icon, hardcoded |
| 5 | `icon_social_twitter_5b7c8b02e0.svg` | https://int-cdn.axa.it/NLPCC_STRAPI_PCC/icon_social_twitter_5b7c8b02e0.svg | `app/[locale]/(public)/login-dhp/page.tsx:231` | social icon, hardcoded |

### Implication for Media cleanup + binary deletion
- The dedup matches files by the Strapi `hash` (in the URL). If one of these 5 files is the **redundant**
  copy in a duplicate group and binary deletion is **enabled**, its binary would be removed from the
  store and the hardcoded URL would 404.
- Mitigation: when running binary deletion, ensure these 5 hashes are kept (set them as the
  **canonical** in their group, or exclude their group). Better long-term: move these to content
  relations or to local `public/` assets so they no longer depend on a CDN binary.

## Local app assets (not Strapi — safe)

`public/` holds **179** bundled static files (e.g. `public/images/dcmc/disease/icon-*.svg`). These are
shipped with the Next app, not served by Strapi, and are unaffected by Strapi media dedup.

## Search method (reproducible)

```
# hardcoded CDN asset URLs
grep -rinoE "https?://[a-z0-9.-]*cdn[a-z0-9.-]*/[A-Za-z0-9_./%-]+\.(svg|png|jpe?g|gif|webp|pdf)" app utils public
# Strapi hash-filename pattern (name_<hex>.ext) anywhere
grep -rinoE "[A-Za-z0-9_%-]+_[0-9a-f]{8,}\.(svg|png|jpe?g|gif|webp|pdf)" app utils
# /uploads/ literals  -> none found
grep -rinE "/uploads/" app utils
```
