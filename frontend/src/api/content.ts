import { http } from './http';

export interface DupEntryInfo {
  id: number;
  documentId: string;
  label: string;
  locale?: string | null;
  createdAt?: string | null;
  refs: number;
}

export interface ContentDupGroup {
  key: string;
  kind: 'CERTAIN' | 'SUSPECT';
  label: string;
  totalRefs: number;
  entries: DupEntryInfo[];
  suggestedCanonicalId: number;
}

export interface ContentTableDuplicatesReport {
  instanceId: number;
  table: string;
  apiUid: string;
  displayName: string;
  totalEntries: number;
  certainGroups: ContentDupGroup[];
  suspectGroups: ContentDupGroup[];
  removableEntries: number;
}

export interface ContentTableSummary {
  table: string;
  apiUid: string;
  displayName: string;
  totalEntries: number;
  certainGroups: number;
  suspectGroups: number;
  removableEntries: number;
}

export interface ContentDuplicatesSummary {
  instanceId: number;
  tablesScanned: number;
  tables: ContentTableSummary[];
}

export interface ContentReference {
  ownerTable: string;
  ownerId: number;
  field: string | null;
  lnkTable: string;
  ownerLabel?: string | null;
  ownerDocumentId?: string | null;
}

export interface ContentReferencesResponse {
  table: string;
  entryId: number;
  total: number;
  references: ContentReference[];
}

export interface ContentDedupGroupRequest {
  canonicalId: number;
  redundantIds: number[];
}

export interface ContentDedupTableRequest {
  table: string;
  groups: ContentDedupGroupRequest[];
}

export interface ContentDedupRequest {
  tables: ContentDedupTableRequest[];
}

export interface ContentDedupAction {
  table: string;
  canonicalId: number;
  redundantIds: number[];
  lnkRepoints: number;
  collisionsRemoved: number;
  entriesDeleted: number;
  cmpsDeleted: number;
  ownLnkRowsDeleted: number;
}

export interface ContentDedupReport {
  applied: boolean;
  groupsProcessed: number;
  refsRepointed: number;
  collisionsRemoved: number;
  entriesDeleted: number;
  backupSchema?: string | null;
  actions: ContentDedupAction[];
}

export const contentApi = {
  scanAll: (instanceId: number) =>
    http.get<ContentDuplicatesSummary>(`/api/instances/${instanceId}/content/duplicates`).then((r) => r.data),
  scanTable: (instanceId: number, table: string) =>
    http.get<ContentTableDuplicatesReport>(`/api/instances/${instanceId}/content/duplicates/${table}`).then((r) => r.data),
  references: (instanceId: number, table: string, entryId: number) =>
    http
      .get<ContentReferencesResponse>(`/api/instances/${instanceId}/content/references`, { params: { table, entryId } })
      .then((r) => r.data),
  dedup: (instanceId: number, tables: ContentDedupTableRequest[], apply: boolean) =>
    http
      .post<ContentDedupReport>(`/api/instances/${instanceId}/content/dedup`, { tables }, { params: { apply } })
      .then((r) => r.data),
};
