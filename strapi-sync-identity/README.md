# strapi-sync-identity (Phase 1 — identity layer)

Drop-in package for **each Strapi instance** (int / preprod / prod) that gives every content entry a
stable, cross-instance identity (`sync_id`) **without touching any content-type schema and without
involving developers**.

It is consumed by **strapi-sync-wizard**, which matches content across instances by an exact JOIN on
`sync_id` instead of fragile positional/heuristic matching.

## What it adds

1. **A sidecar table `sync_identity`** in the instance's Postgres (`public` schema):

   | column      | type        | notes                                    |
   |-------------|-------------|------------------------------------------|
   | sync_id     | uuid        | shared identity of the logical entity    |
   | uid         | varchar     | e.g. `api::article.article`              |
   | document_id | varchar     | Strapi v5 documentId                     |
   | locale      | varchar     | null for non-localized content           |
   | created_at  | timestamptz | default now()                            |

   Unique on `(document_id, COALESCE(locale,''))`. It is **not** a content-type, so Strapi never
   exposes it or manages it. Because it lives in `public`, it travels automatically with the Postgres
   snapshots the sync tool takes (air-gap artifact).

2. **A global `strapi.documents.use` middleware** that assigns a fresh `sync_id` whenever an editor
   **creates** (or clones) an entry in the Strapi admin. Identity is therefore assigned **at the
   origin** and is direction-agnostic.

> Content moved between instances by the sync tool inherits the **source** `sync_id` (the tool writes
> the sidecar directly after apply — "anti-drift"). The random id the middleware would assign on the
> tool's own API writes is harmless because the tool overwrites it.

## Install

1. Copy `database/migrations/2026.06.22T100000_create_sync_identity.js` into your Strapi project's
   `database/migrations/` folder. It runs automatically on next boot. (Or run `database/sync_identity.sql`
   manually against the instance DB.)

2. Register the middleware. In `src/index.ts` (TypeScript) or `src/index.js`, call the helper from
   `register()` (or `bootstrap()`):

   ```ts
   import { registerSyncIdentity } from "./sync-identity"; // copy sync-identity.ts next to index.ts

   export default {
     register({ strapi }) {
       registerSyncIdentity(strapi);
     },
     bootstrap() {},
   };
   ```

   If you already have a `register`, just add the `registerSyncIdentity(strapi)` call inside it.

3. Boot Strapi once so the migration runs, then **bootstrap existing content** by calling the sync
   tool endpoint (it backfills `sync_identity` for all current entries via direct DB access):

   ```
   POST /api/instances/{instanceId}/identity/backfill
   ```

4. **Cross-instance linking** (give the same `sync_id` to the same logical entity on two instances):
   from the sync tool, on a merge request between the two instances:

   ```
   POST /api/merge-requests/{id}/identity/reconcile?apply=false   # dry-run: see proposed links
   POST /api/merge-requests/{id}/identity/reconcile?apply=true    # write the shared sync_ids
   ```

## Notes / assumptions

- Requires PostgreSQL ≥ 13 (`gen_random_uuid()` in core). For older versions enable `pgcrypto`.
- Assumes the Strapi DB schema is `public` (Strapi default). If you use a custom schema, the unique
  index/raw SQL and the sync tool's snapshot logic must be adjusted accordingly.
- One `sync_id` per `(document_id, locale)`. Multilingual policy (sharing one id across locales of a
  document) is a future refinement.
- Components / dynamic-zone entries get **no** `sync_id` — they have no documentId and are handled by
  the relation machinery.
