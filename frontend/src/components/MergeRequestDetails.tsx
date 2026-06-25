import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from 'primereact/button';
import { Message } from 'primereact/message';
import { Toast } from 'primereact/toast';
import { ProgressSpinner } from 'primereact/progressspinner';

import {
  ContentTypeComparisonResultKind,
  MergeRequestData,
  MergeRequestDetail,
  StrapiContentTypeKind,
} from '../types';
import {
  mrKeys,
  useMergeRequest,
  useCheckSchema,
  useCompareContent,
  useUpdateSelection,
  useCompleteMerge,
  useUpdateMergeRequest,
  useImportSchema,
  useImportPrefetch,
  type UnifiedSelection,
} from '../api/mergeRequests';
import { apiErrorMessage } from '../api/http';

import SchemaCompatibilityStep from './steps/SchemaCompatibilityStep';
import ContentComparisonStep from './steps/ContentComparisonStep';
import MergeFilesWorkspace from './steps/MergeFilesWorkspace';
import MergeContentWorkspace from './steps/MergeContentWorkspace';
import CompleteMergeStep from './steps/CompleteMergeStep';
import SnapshotManager from './SnapshotManager';
import IdentityReconciliationDialog from './steps/IdentityReconciliationDialog';

const RANK: Record<string, number> = {
  CREATED: 0, SCHEMA_CHECKED: 1, COMPARED: 2, MERGED_FILES: 3,
  MERGED_SINGLES: 4, MERGED_COLLECTIONS: 5, REVIEW: 6, IN_PROGRESS: 6, FAILED: 6, COMPLETED: 7,
};

const statusBadge = (s: string): { label: string; cls: string } => {
  if (s === 'COMPLETED') return { label: 'Completed', cls: 'success' };
  if (s === 'FAILED') return { label: 'Failed', cls: 'danger' };
  if (s === 'REVIEW' || s === 'IN_PROGRESS') return { label: s === 'REVIEW' ? 'Review' : 'In progress', cls: 'warn' };
  if (s === 'COMPARED') return { label: 'Compared', cls: 'info' };
  return { label: s.replace(/_/g, ' ').toLowerCase(), cls: '' };
};

const MergeRequestDetails: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const idNum = parseInt(id || '0', 10);
  const navigate = useNavigate();
  const toast = useRef<Toast>(null);
  const queryClient = useQueryClient();

  const { data: mr, isLoading, error, refetch } = useMergeRequest(idNum);

  const [activeStep, setActiveStep] = useState<number>(0);
  const [showReconcile, setShowReconcile] = useState(false);
  const [schemaResult, setSchemaResult] = useState<{ isCompatible: boolean; blocking: string[]; warnings: string[] } | null>(null);

  const schemaInputRef = useRef<HTMLInputElement>(null);
  const prefetchInputRef = useRef<HTMLInputElement>(null);

  const checkSchemaMut = useCheckSchema(idNum);
  const compareMut = useCompareContent(idNum);
  const selectionMut = useUpdateSelection(idNum);
  const completeMut = useCompleteMerge(idNum);
  const updateMut = useUpdateMergeRequest(idNum);
  const importSchemaMut = useImportSchema(idNum);
  const importPrefetchMut = useImportPrefetch(idNum);

  const showError = (detail: string, summary = 'Error') => toast.current?.show({ severity: 'error', summary, detail, life: 5000 });

  useEffect(() => {
    if (!mr) return;
    const r = RANK[mr.mergeRequest.status] ?? 0;
    setActiveStep(r >= 3 ? 1 : 0); // 0 = files, 1 = content
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mr?.mergeRequest.id]);

  const patchMergeData = (data?: MergeRequestData) => {
    if (!data) return;
    queryClient.setQueryData<MergeRequestDetail>(mrKeys.detail(idNum), (old) => (old ? { ...old, mergeRequestData: data } : old));
  };

  const onSchemaFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const parsed = JSON.parse(await file.text());
      const res: any = await importSchemaMut.mutateAsync(parsed && parsed.schema ? parsed : { schema: parsed });
      toast.current?.show({ severity: res?.isCompatible ? 'success' : 'warn', summary: 'Schema imported', detail: res?.isCompatible ? 'Compatible with target' : 'Not compatible with target', life: 4000 });
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to import schema'); }
    finally { if (schemaInputRef.current) schemaInputRef.current.value = ''; }
  };

  const onPrefetchFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await importPrefetchMut.mutateAsync(JSON.parse(await file.text()));
      toast.current?.show({ severity: 'success', summary: 'Prefetch imported', detail: 'You can now run Compare.', life: 4000 });
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to import prefetch'); }
    finally { if (prefetchInputRef.current) prefetchInputRef.current.value = ''; }
  };

  const checkSchemaCompatibility = async (force = false) => {
    try {
      const res: any = await checkSchemaMut.mutateAsync(force);
      setSchemaResult({ isCompatible: !!res?.isCompatible, blocking: res?.blocking ?? [], warnings: res?.warnings ?? [] });
      toast.current?.show({ severity: res?.isCompatible ? 'success' : 'warn', summary: res?.isCompatible ? 'Compatible' : 'Incompatible', detail: res?.isCompatible ? 'Source and target schemas are compatible.' : 'Schemas are not compatible.', life: 5000 });
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to check schema'); }
  };

  const compareContent = async () => {
    try {
      await compareMut.mutateAsync('full');
      toast.current?.show({ severity: 'success', summary: 'Compared', detail: 'Content comparison completed.', life: 4000 });
      setActiveStep(0);
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to compare content'); }
  };

  const updateMergeRequestStatus = async (status: string): Promise<boolean> => {
    if (!mr) return false;
    try {
      await updateMut.mutateAsync({
        id: mr.mergeRequest.id, name: mr.mergeRequest.name, description: mr.mergeRequest.description,
        sourceInstanceId: mr.mergeRequest.sourceInstance.id, targetInstanceId: mr.mergeRequest.targetInstance.id, status,
      });
      return true;
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to update status'); return false; }
  };

  const proceedToNextStep = async (nextStep: number) => {
    if (!mr) return;
    const status = mr.mergeRequest.status;
    if (nextStep > activeStep && !['IN_PROGRESS', 'COMPLETED', 'FAILED'].includes(status)) {
      if (nextStep <= 1) await updateMergeRequestStatus('MERGED_FILES');
      else if (nextStep === 2) await updateMergeRequestStatus('MERGED_SINGLES');
      else if (nextStep === 3) await updateMergeRequestStatus('MERGED_COLLECTIONS');
      else await updateMergeRequestStatus('REVIEW');
    }
    setActiveStep(Math.min(nextStep, 2));
  };

  const updateAllSelections = async (
    kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string,
    documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind
  ): Promise<boolean> => {
    try {
      await selectionMut.mutateAsync({ kind: kind as unknown as UnifiedSelection['kind'], tableName, ids: documentIds, selectAllKind: selectAllKind as unknown as string, isSelected });
      return true;
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to update selections'); return false; }
  };

  const completeMerge = async (opts?: { onlyFailed?: boolean; rollbackOnFailure?: boolean }) => {
    try {
      const res: any = await completeMut.mutateAsync(opts);
      const failed = typeof res?.failed === 'number' ? res.failed : 0;
      if (failed > 0) {
        toast.current?.show({ severity: 'warn', summary: 'Completed with errors', detail: res?.message || `${failed} item(s) failed`, life: 7000 });
      } else {
        toast.current?.show({ severity: 'success', summary: 'Merge completed', detail: res?.message, life: 5000 });
      }
    } catch (err) { showError(apiErrorMessage(err) || 'Failed to complete merge'); }
  };

  if (isLoading) return <div className="flex justify-content-center p-5"><ProgressSpinner /></div>;
  if (error || !mr) {
    return (
      <div>
        <Toast ref={toast} />
        <Message severity={error ? 'error' : 'warn'} text={error ? apiErrorMessage(error) : 'Merge request not found'} className="w-full mb-3" />
        <button className="ss-btn" onClick={() => navigate('/merge-requests')}><i className="pi pi-arrow-left" aria-hidden="true" /> Back to merge requests</button>
      </div>
    );
  }

  const detail = mr;
  const status = detail.mergeRequest.status;
  const rank = RANK[status] ?? 0;
  const phase: 'prep' | 'merge' | 'review' = rank < 2 ? 'prep' : rank < 6 ? 'merge' : 'review';
  const completed = status === 'COMPLETED';
  const bothReal = !detail.mergeRequest.sourceInstance.isVirtual && !detail.mergeRequest.targetInstance.isVirtual;
  const data = detail.mergeRequestData;
  const badge = statusBadge(status);

  // counts for tabs
  const fileCount = (data?.files ? (Object.values(data.files).flat() as any[]).filter((f) => f && f.compareState && f.compareState !== 'IDENTICAL').length : 0);
  const contentCount = (() => {
    let n = 0;
    Object.values(data?.singleTypes || {}).forEach((r: any) => { if (r?.compareState && r.compareState !== 'IDENTICAL') n++; });
    Object.values(data?.collectionTypes || {}).forEach((arr: any) => (arr || []).forEach((r: any) => { if (r?.compareState && r.compareState !== 'IDENTICAL') n++; }));
    return n;
  })();

  const Chip: React.FC<{ cls: string; icon: string; label: string; onClick?: () => void }> = ({ cls, icon, label, onClick }) => (
    <span className={`ss-chip ${cls}`} style={onClick ? { cursor: 'pointer' } : undefined} onClick={onClick}>
      <i className={icon} aria-hidden="true" /> {label}
    </span>
  );

  const schemaChip = detail.isCompatible === true
    ? <Chip cls="success" icon="pi pi-check" label="Schema compatible" />
    : detail.isCompatible === false
      ? <Chip cls="danger" icon="pi pi-times" label="Schema incompatible" />
      : <Chip cls="" icon="pi pi-shield" label="Schema unchecked" />;

  return (
    <div>
      <Toast ref={toast} />

      <div className="ss-review">
        {/* Header */}
        <div className="ss-review-head">
          <div className="ss-card-row">
            <Button icon="pi pi-arrow-left" rounded text aria-label="Back" onClick={() => navigate('/merge-requests')} />
            <div style={{ minWidth: 0 }}>
              <div className="ss-card-row" style={{ gap: 9 }}>
                <span style={{ fontSize: 15, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 420 }}>{detail.mergeRequest.name}</span>
                <span className={`ss-badge ${badge.cls}`}>{badge.label}</span>
              </div>
              <div className="ss-route" style={{ marginTop: 5 }}>
                <span className="ss-node"><span className="ss-dot" />{detail.mergeRequest.sourceInstance.name}</span>
                <i className="pi pi-arrow-right" aria-hidden="true" />
                <span className="ss-node"><span className="ss-dot target" />{detail.mergeRequest.targetInstance.name}</span>
              </div>
            </div>
            <span style={{ marginLeft: 'auto' }} />
            {phase === 'prep' && (
              <button className="ss-btn primary" disabled={detail.isCompatible !== true || compareMut.isPending} onClick={compareContent}>
                <i className="pi pi-search" aria-hidden="true" /> {compareMut.isPending ? 'Comparing…' : 'Compare'}
              </button>
            )}
            {phase === 'merge' && (
              <>
                <button className="ss-btn subtle" disabled={compareMut.isPending} onClick={compareContent}><i className="pi pi-refresh" aria-hidden="true" /> Re-compare</button>
                <button className="ss-btn primary" disabled={completed} onClick={() => updateMergeRequestStatus('REVIEW').then(() => refetch())}>
                  <i className="pi pi-flag" aria-hidden="true" /> Go to review
                </button>
              </>
            )}
          </div>

          {/* readiness chips */}
          <div className="ss-card-row" style={{ gap: 6, marginTop: 11, flexWrap: 'wrap' }}>
            {schemaChip}
            {schemaResult && schemaResult.warnings.length > 0 && <Chip cls="warn" icon="pi pi-exclamation-triangle" label={`${schemaResult.warnings.length} warning${schemaResult.warnings.length === 1 ? '' : 's'}`} />}
            {bothReal && rank >= 2 && <Chip cls="info" icon="pi pi-id-card" label="Reconcile identity" onClick={() => setShowReconcile(true)} />}
            {rank >= 2 && (
              <>
                <span style={{ marginLeft: 'auto' }} />
                <span className="ss-dim" style={{ fontSize: 11 }}>{fileCount} files · {contentCount} content changes</span>
              </>
            )}
          </div>
        </div>

        {/* Body */}
        {phase === 'prep' && (
          <div style={{ padding: 16 }}>
            <div className="mb-3 flex gap-2 flex-wrap">
              <input type="file" accept="application/json" style={{ display: 'none' }} ref={schemaInputRef} onChange={onSchemaFileSelected} />
              <button className="ss-btn" onClick={() => schemaInputRef.current?.click()} disabled={importSchemaMut.isPending}><i className="pi pi-upload" aria-hidden="true" /> Import schema JSON</button>
              <input type="file" accept="application/json" style={{ display: 'none' }} ref={prefetchInputRef} onChange={onPrefetchFileSelected} />
              <button className="ss-btn" onClick={() => prefetchInputRef.current?.click()} disabled={importPrefetchMut.isPending}><i className="pi pi-upload" aria-hidden="true" /> Import prefetch JSON</button>
            </div>
            <SchemaCompatibilityStep schemaCompatible={detail.isCompatible === true} checkingSchema={checkSchemaMut.isPending} checkSchemaCompatibility={checkSchemaCompatibility} blocking={schemaResult?.blocking ?? []} warnings={schemaResult?.warnings ?? []} />
            <div style={{ borderTop: '1px solid var(--ss-border)', marginTop: 16, paddingTop: 16 }}>
              <ContentComparisonStep comparingContent={compareMut.isPending} schemaCompatible={detail.isCompatible === true} compareContent={compareContent} status={status} />
            </div>
          </div>
        )}

        {phase === 'merge' && (
          <>
            <div className="ss-tabs" style={{ padding: '0 16px', marginBottom: 0 }}>
              <button className={`ss-tab${activeStep === 0 ? ' active' : ''}`} onClick={() => setActiveStep(0)}>Files <span className="ss-tab-n">{fileCount}</span></button>
              <button className={`ss-tab${activeStep === 1 ? ' active' : ''}`} onClick={() => setActiveStep(1)}>Content <span className="ss-tab-n">{contentCount}</span></button>
            </div>
            {activeStep === 0 && (
              <MergeFilesWorkspace mergeRequestId={detail.mergeRequest.id}
                sourceInstanceId={detail.mergeRequest.sourceInstance.id} targetInstanceId={detail.mergeRequest.targetInstance.id}
                filesData={data?.files}
                updateAllSelections={updateAllSelections} selections={data?.selections || []} allMergeData={data!} onSaved={patchMergeData} />
            )}
            {activeStep === 1 && data && (
              <MergeContentWorkspace mergeRequestId={idNum} data={data}
                sourceInstanceId={detail.mergeRequest.sourceInstance.id} targetInstanceId={detail.mergeRequest.targetInstance.id}
                updateAllSelections={updateAllSelections} onSaved={() => { refetch(); }} />
            )}
          </>
        )}

        {phase === 'review' && (
          <div style={{ padding: 16 }}>
            <SnapshotManager mergeRequestId={idNum} onRestoreComplete={() => refetch()} />
            <div style={{ marginTop: 16 }}>
              <CompleteMergeStep status={status} completing={completeMut.isPending} completeMerge={completeMerge}
                selections={data?.selections} allMergeData={data} />
            </div>
            {!completed && (
              <div style={{ marginTop: 16 }}>
                <button className="ss-btn" onClick={() => { setActiveStep(1); updateMergeRequestStatus('MERGED_COLLECTIONS').then(() => refetch()); }}>
                  <i className="pi pi-arrow-left" aria-hidden="true" /> Back to changes
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      <IdentityReconciliationDialog mergeRequestId={idNum} visible={showReconcile} onHide={() => setShowReconcile(false)} />
    </div>
  );
};

export default MergeRequestDetails;
