import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from './http';
import type { StrapiInstance } from '../types';

export interface InstanceDTO {
  id?: number;
  name: string;
  url: string;
  username: string;
  password: string;
  apiKey: string;
  isVirtual?: boolean;
  dbHost?: string | null;
  dbPort?: number | null;
  dbName?: string | null;
  dbSchema?: string | null;
  dbUser?: string | null;
  dbPassword?: string | null;
  dbSslMode?: string | null;
}

export interface ConnectionTestResult {
  connected: boolean;
  message: string;
}

export const instanceKeys = {
  all: ['instances'] as const,
  list: () => [...instanceKeys.all, 'list'] as const,
};

export const instancesApi = {
  list: () => http.get<StrapiInstance[]>('/api/instances').then((r) => r.data),
  create: (dto: InstanceDTO) => http.post('/api/instances', dto).then((r) => r.data),
  update: (id: number, dto: InstanceDTO) => http.put(`/api/instances/${id}`, dto).then((r) => r.data),
  remove: (id: number) => http.delete(`/api/instances/${id}`).then((r) => r.data),
  testDb: (id: number) => http.post<ConnectionTestResult>(`/api/instances/${id}/test-db`).then((r) => r.data),
  testLogin: (id: number) => http.post<ConnectionTestResult>(`/api/instances/${id}/test-login`).then((r) => r.data),
  testToken: (id: number) => http.post<ConnectionTestResult>(`/api/instances/${id}/test-token`).then((r) => r.data),
  backfillIdentity: (id: number) =>
    http.post<{ instanceId: number; inserted: number }>(`/api/instances/${id}/identity/backfill`).then((r) => r.data),
};

export function useInstances() {
  return useQuery({ queryKey: instanceKeys.list(), queryFn: instancesApi.list });
}

export function useCreateInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: instancesApi.create,
    onSuccess: () => qc.invalidateQueries({ queryKey: instanceKeys.all }),
  });
}

export function useUpdateInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, dto }: { id: number; dto: InstanceDTO }) => instancesApi.update(id, dto),
    onSuccess: () => qc.invalidateQueries({ queryKey: instanceKeys.all }),
  });
}

export function useDeleteInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: instancesApi.remove,
    onSuccess: () => qc.invalidateQueries({ queryKey: instanceKeys.all }),
  });
}
