# Draft & Publish synchronization (Strapi v5)

StrapiSync can optionally reproduce the **Draft & Publish** state of each document on the target,
not just the published version. This is opt-in per merge request via the **Include drafts** toggle.

## Background: how Strapi v5 models drafts

With Draft & Publish enabled, a single `documentId` can exist as up to **two rows** in its table:

- the **published** row (`published_at` is set)
- the **draft** row (`published_at IS NULL`)

They share the same `documentId` (and `locale`) but have different numeric `id`s, and each row has
its **own** components and relations (the `_cmps` / `_lnk` rows reference the entity row id). So the
draft is effectively a second, parallel content graph.

A document is therefore in one of these states:

| State        | Rows present                    | Meaning                                            |
|--------------|---------------------------------|----------------------------------------------------|
| Published    | published (+ identical draft)   | The normal, clean state                            |
| Modified     | published + **divergent** draft | Published, with unsaved/unpublished draft edits    |
| Draft-only   | draft only                      | Never published, or explicitly unpublished         |
| (no D&P)     | single published row            | Content type without Draft & Publish               |

## What the tool does

### Reading

When *Include drafts* is on, the comparison reads **both** rows per document and merges them into a
single logical entry:

- the **primary body** holds the published version (or the draft, when the document is draft-only —
  flagged by `metadata.isDraftOnly`);
- a **draft overlay** (`StrapiContent.draft`) is attached **only** when the draft diverges from the
  published version. When draft == published (the overwhelmingly common case) the overlay collapses,
  so a normal sync produces no extra work.

The matching/identity tiers (shared `sync_id`, content fingerprint, natural key, positional) are
**unchanged** — drafts ride on the same per-document identity, because `sync_identity` is keyed on
`(document_id, locale)`, independent of publish status.

### Comparing

Two documents are *identical* only when **both** the published body and the draft body match. So:

- a divergent draft on the source surfaces as a `DIFFERENT` item even if the published body is
  identical (the diff panel shows the **draft** body for these "modified" items);
- a draft-only source document vs. a published target (or vice-versa) surfaces as `DIFFERENT`.

### Writing

The apply step reproduces the state with at most two calls per document:

| Source state | Target write sequence                                                          |
|--------------|-------------------------------------------------------------------------------|
| Published    | upsert (publishes) — the legacy behavior                                       |
| Modified     | upsert the published body, then `PUT ?status=draft` with the draft body        |
| Draft-only   | `POST/PUT ?status=draft` (creates/updates the draft, leaves it unpublished)    |
| Unpublished  | admin content-manager `actions/unpublish`                                      |

Publish/unpublish use the **admin content-manager API** (`/content-manager/.../actions/publish`),
authenticated with the admin JWT the client already obtains via `getLoginToken()`. Everything else
uses the public REST API.

The document mapping is created once per document (status-independent), so re-running a sync stays
idempotent and relations resolve correctly regardless of which channel a related document lives in.

## Enabling it

- **New merge request**: tick *Include drafts* at creation.
- **Existing merge request**: click the **Drafts: off → on** chip in the merge-request header, then
  re-run **Compare**.

> Note: turning drafts on surfaces *all* pre-existing draft divergences between the two environments,
> not just your latest edit — that content was simply invisible before. This is expected.

## Scope / limitations

- The feature targets Strapi **v5** Draft & Publish semantics.
- Content types without Draft & Publish are unaffected (single published row, legacy path).
- Locale is honored via the identity layer; each `(document_id, locale)` is reconciled independently.
