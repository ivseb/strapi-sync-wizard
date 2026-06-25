import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from './http';
import type { MergeRequestDetail, IdentityReconciliationReport } from '../types';

export type CompareMode = 'full' | 'cache' | 'compare';

export interface MergeVerificationItem {
  contentType: string;
  documentId: string;
  direction: string;
  expected: string;
  actual: string;
  consistent: boolean;
  severity: 'ok' | 'schema_gap' | 'mismatch';
  reason?: string | null;
}
export interface MergeVerificationReport {
  mergeRequestId: number;
  total: number;
  consistent: number;
  schemaGap: number;
  inconsistent: number;
  items: MergeVerificationItem[];
}

export interface CreateMergeRequestDTO {
  name: string;
  description?: string;
  sourceInstanceId: number;
  targetInstanceId: number;
  includeDrafts?: boolean;
}

export interface UnifiedSelection {
  kind: 'singleType' | 'collectionType' | 'files' | 'component';
  tableName?: string | null;
  ids?: string[] | null;
  selectAllKind?: string | null;
  isSelected: boolean;
}

export interface UpdateMergeRequestDTO {
  id: number;
  name: string;
  description?: string;
  sourceInstanceId: number;
  targetInstanceId: number;
  status?: string;
  includeDrafts?: boolean;
}

export interface MergeRequestListParams {
  completed?: boolean | null;
  sortBy?: string;
  sortOrder?: 'ASC' | 'DESC';
  page?: number;
  pageSize?: number;
}

// ---- query keys --------------------------------------------------------------
export const mrKeys = {
  all: ['merge-requests'] as const,
  list: (params?: MergeRequestListParams) => [...mrKeys.all, 'list', params ?? {}] as const,
  detail: (id: number) => [...mrKeys.all, 'detail', id] as const,
};

// ---- raw endpoint functions --------------------------------------------------
export const mergeRequestsApi = {
  list: (params?: MergeRequestListParams) => http.get('/api/merge-requests', { params }).then((r) => r.data),
  get: (id: number) => http.get<MergeRequestDetail>(`/api/merge-requests/${id}`).then((r) => r.data),
  create: (dto: CreateMergeRequestDTO) =>
    http.post('/api/merge-requests', dto).then((r) => r.data),
  remove: (id: number) => http.delete(`/api/merge-requests/${id}`).then((r) => r.data),
  compare: (id: number, mode: CompareMode) =>
    http.post(`/api/merge-requests/${id}/compare`, null, { params: { mode } }).then((r) => r.data),
  update: (id: number, dto: UpdateMergeRequestDTO) =>
    http.put(`/api/merge-requests/${id}`, dto).then((r) => r.data),
  checkSchema: (id: number, force: boolean) =>
    http.post(`/api/merge-requests/${id}/check-schema`, null, { params: { force } }).then((r) => r.data),
  importSchema: (id: number, body: unknown) =>
    http.post(`/api/merge-requests/${id}/import/schema`, body).then((r) => r.data),
  importPrefetch: (id: number, body: unknown) =>
    http.post(`/api/merge-requests/${id}/import/prefetch`, body).then((r) => r.data),
  updateSelection: (id: number, selection: UnifiedSelection) =>
    http.post(`/api/merge-requests/${id}/selection`, selection).then((r) => r.data),
  complete: (id: number, opts?: { onlyFailed?: boolean; rollbackOnFailure?: boolean }) =>
    http.post(`/api/merge-requests/${id}/complete`, null, { params: opts }).then((r) => r.data),
  verify: (id: number) =>
    http.post<MergeVerificationReport>(`/api/merge-requests/${id}/verify`).then((r) => r.data),
  reconcileIdentity: (id: number, apply: boolean) =>
    http
      .post<IdentityReconciliationReport>(`/api/merge-requests/${id}/identity/reconcile`, null, {
        params: { apply },
      })
      .then((r) => r.data),
};

// ---- hooks -------------------------------------------------------------------
export function useMergeRequests(params?: MergeRequestListParams) {
  return useQuery({ queryKey: mrKeys.list(params), queryFn: () => mergeRequestsApi.list(params) });
}

export function useMergeRequest(id: number, enabled = true) {
  return useQuery({
    queryKey: mrKeys.detail(id),
    queryFn: () => mergeRequestsApi.get(id),
    enabled: enabled && Number.isFinite(id) && id > 0,
  });
}

export function useCreateMergeRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: mergeRequestsApi.create,
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.all }),
  });
}

export function useDeleteMergeRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: mergeRequestsApi.remove,
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.all }),
  });
}

export function useCompareContent(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (mode: CompareMode) => mergeRequestsApi.compare(id, mode),
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.detail(id) }),
  });
}

export function useUpdateSelection(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (selection: UnifiedSelection) => mergeRequestsApi.updateSelection(id, selection),
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.detail(id) }),
  });
}

export function useCompleteMerge(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (opts?: { onlyFailed?: boolean; rollbackOnFailure?: boolean }) => mergeRequestsApi.complete(id, opts),
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.detail(id) }),
  });
}

export function useReconcileIdentity(id: number) {
  return useMutation({
    mutationFn: (apply: boolean) => mergeRequestsApi.reconcileIdentity(id, apply),
  });
}

export function useUpdateMergeRequest(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (dto: UpdateMergeRequestDTO) => mergeRequestsApi.update(id, dto),
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.detail(id) }),
  });
}

export function useCheckSchema(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (force: boolean) => mergeRequestsApi.checkSchema(id, force),
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.detail(id) }),
  });
}

export function useImportSchema(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: unknown) => mergeRequestsApi.importSchema(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: mrKeys.detail(id) }),
  });
}

export function useImportPrefetch(id: number) {
  return useMutation({
    mutationFn: (body: unknown) => mergeRequestsApi.importPrefetch(id, body),
  });
}
