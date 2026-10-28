import { ContentTypeLabel } from '../types';

/** How a content type is shown: the name editors see in the Strapi admin + its API id. */
export interface TypeName {
  admin: string;
  api: string;
  table?: string;
}

const FILES: TypeName = { admin: 'Files', api: 'files', table: 'files' };

export const apiIdOf = (uid: string): string => uid.slice(uid.lastIndexOf('.') + 1);

const humanize = (s: string): string => s.replace(/[-_]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

/** Resolver from a uid ("api::x.x") or a table name ("xs") to the type's display name. */
export const makeTypeNamer = (labels: ContentTypeLabel[] = []) => {
  const byUid = new Map(labels.map((l) => [l.uid, l]));
  const byTable = new Map(labels.map((l) => [l.tableName, l]));
  return (key: string): TypeName => {
    if (key === 'files' || key === 'plugin::upload.file') return FILES;
    const l = byUid.get(key) ?? byTable.get(key);
    if (l) return { admin: l.displayName, api: apiIdOf(l.uid), table: l.tableName };
    // Unknown (comparison made before labels existed): best effort from the key itself.
    const api = key.includes('.') ? apiIdOf(key) : key;
    return { admin: humanize(api), api, table: key.includes('::') ? undefined : key };
  };
};

export type TypeNamer = ReturnType<typeof makeTypeNamer>;

/** "Section Card Assistance (section-card-assistance)" — plain text, e.g. for search haystacks. */
export const typeText = (n: TypeName): string => (n.api === 'files' ? n.admin : `${n.admin} (${n.api})`);
