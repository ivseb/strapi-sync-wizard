import { http } from './http';

export interface DupFileInfo {
  id: number;
  documentId: string;
  name: string;
  ext: string;
  sizeBytes: number;
  folder: string;
  mime: string;
  url: string;
  sha: string | null;
  refs: number;
  refsByType: Record<string, number>;
}

export interface DupGroup {
  key: string;
  kind: 'CERTAIN' | 'SUSPECT';
  sha: string | null;
  name: string;
  totalRefs: number;
  files: DupFileInfo[];
  suggestedCanonicalId: number;
}

export interface MediaDuplicatesReport {
  instanceId: number;
  totalFiles: number;
  certainGroups: DupGroup[];
  suspectGroups: DupGroup[];
  removableCopies: number;
}

export interface DedupGroupRequest {
  canonicalId: number;
  redundantIds: number[];
}

export interface DedupReport {
  applied: boolean;
  groupsProcessed: number;
  refsRepointed: number;
  filesDeleted: number;
  backupSchema?: string | null;
  binariesRequested?: boolean;
  binariesDeleted?: number;
  binariesFailed?: number;
}

export interface FileReference {
  relatedType: string;
  field: string | null;
  relatedId: number;
  label: string | null;
  documentId: string | null;
  isComponent: boolean;
}

export interface FileReferencesResponse {
  fileId: number;
  total: number;
  references: FileReference[];
}

export const mediaApi = {
  scan: (instanceId: number) =>
    http.get<MediaDuplicatesReport>(`/api/instances/${instanceId}/media/duplicates`).then((r) => r.data),
  references: (instanceId: number, fileId: number) =>
    http.get<FileReferencesResponse>(`/api/instances/${instanceId}/media/file/references`, { params: { fileId } }).then((r) => r.data),
  dedup: (instanceId: number, groups: DedupGroupRequest[], apply: boolean, deleteBinaries = false) =>
    http
      .post<DedupReport>(`/api/instances/${instanceId}/media/dedup`, { groups }, { params: { apply, deleteBinaries } })
      .then((r) => r.data),
};
