import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from 'primereact/button';
import { Message } from 'primereact/message';
import { Toast } from 'primereact/toast';
import { ProgressSpinner } from 'primereact/progressspinner';
import { Tag } from 'primereact/tag';

import {
  ContentTypeComparisonResultKind,
  ContentTypeComparisonResultWithRelationships,
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
import MergeFilesStep from './steps/MergeFilesStep';
import MergeContentWorkspace from './steps/MergeContentWorkspace';
import CompleteMergeStep from './steps/CompleteMergeStep';
import SnapshotManager from './SnapshotManager';
import IdentityReconciliationDialog from './steps/IdentityReconciliationDialog';

const RANK: Record<string, number> = {
  CREATED: 0, SCHEMA_CHECKED: 1, COMPARED: 2, MERGED_FILES: 3,
  MERGED_SINGLES: 4, MERGED_COLLECTIONS: 5, REVIEW: 6, IN_PROGRESS: 6, FAILED: 6, COMPLETED: 7,
};

const statusSeverity = (s: string): 'success' | 'danger' | 'info' | 'warning' =>
  s === 'COMPLETED' ? 'success' : s === 'FAILED' ? 'danger' : s === 'REVIEW' || s === 'IN_PROGRESS' ? 'warning' : 'info';

interface PipeStep {
  key: string;
  label: string;
  icon: string;
  done: boolean;
  current: boolean;
  onClick?: () => void;
}

const Pipeline: React.FC<{ steps: PipeStep[] }> = ({ steps }) => (
  <div className="ss-pipe surface-card border-round" style={{ border: '1px solid var(--surface-border)' }}>
    {steps.map((s, i) => (
      <React.Fragment key={s.key}>
        {i > 0 && <div className={`ss-pipe-conn${steps[i].done ? ' done' : ''}`} />}
        <button
          type="button"
          className={`ss-pipe-step${s.done ? ' done' : ''}${s.current ? ' current' : ''}${s.onClick ? ' clickable' : ''}`}
          onClick={s.onClick}
          disabled={!s.onClick}
        >
          <span className="ss-pipe-dot"><i className={s.done ? 'pi pi-check' : s.icon} aria-hidden="true" /></span>
          <span className="ss-pipe-label">{s.label}</span>
        </button>
      </React.Fragment>
    ))}
  </div>
);

const MergeRequestDetails: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const idNum = parseInt(id || '0', 10);
  const navigate = useNavigate();
  const toast = useRef<Toast>(null);
  const queryClient = useQueryClient();

  const { data: mr, isLoading, error, refetch } = useMergeRequest(idNum);

  const [activeStep, setActiveStep] = useState<number>(0);
  const [showReconcile, setShowReconcile] = useState(false);
  const [mergingCollections, setMergingCollections] = useState(false);

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

  const mergeSingleTypes = async () => {
    if (await updateMergeRequestStatus('MERGED_SINGLES')) { toast.current?.show({ severity: 'success', summary: 'Single types saved', life: 4000 }); await proceedToNextStep(2); }
  };

  const mergeCollections = async () => {
    setMergingCollections(true);
    try {
      if (await updateMergeRequestStatus('MERGED_COLLECTIONS')) { toast.current?.show({ severity: 'success', summary: 'Collections saved', life: 4000 }); await proceedToNextStep(4); }
    } finally { setMergingCollections(false); }
  };

  const completeMerge = async () => {
    try { await completeMut.mutateAsync(); toast.current?.show({ severity: 'success', summary: 'Merge completed', life: 5000 }); }
    catch (err) { showError(apiErrorMessage(err) || 'Failed to complete merge'); }
  };

  if (isLoading) return <div className="flex justify-content-center p-5"><ProgressSpinner /></div>;
  if (error || !mr) {
    return (
      <div>
        <Toast ref={toast} />
        <Message severity={error ? 'error' : 'warn'} text={error ? apiErrorMessage(error) : 'Merge request not found'} className="w-full mb-3" />
        <Button label="Back to merge requests" icon="pi pi-arrow-left" onClick={() => navigate('/merge-requests')} />
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

  const steps: PipeStep[] = [
    { key: 'schema', label: 'Schema', icon: 'pi pi-shield', done: rank >= 1, current: phase === 'prep' && rank < 1 },
    { key: 'compare', label: 'Compare', icon: 'pi pi-search', done: rank >= 2, current: phase === 'prep' && rank >= 1 },
    { key: 'identity', label: 'Identity', icon: 'pi pi-id-card', done: false, current: false, onClick: rank >= 2 && bothReal ? () => setShowReconcile(true) : undefined },
    { key: 'files', label: 'Files', icon: 'pi pi-images', done: rank >= 3, current: phase === 'merge' && activeStep === 0, onClick: phase === 'merge' ? () => setActiveStep(0) : undefined },
    { key: 'content', label: 'Content', icon: 'pi pi-database', done: rank >= 5, current: phase === 'merge' && activeStep === 1, onClick: phase === 'merge' ? () => setActiveStep(1) : undefined },
    { key: 'complete', label: 'Complete', icon: 'pi pi-flag', done: completed, current: phase === 'review' },
  ];

  return (
    <div>
      <Toast ref={toast} />

      <div className="ss-page-head">
        <div className="flex align-items-center gap-3" style={{ minWidth: 0 }}>
          <Button icon="pi pi-arrow-left" rounded text aria-label="Back" onClick={() => navigate('/merge-requests')} />
          <div style={{ minWidth: 0 }}>
            <h2 style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{detail.mergeRequest.name}</h2>
            <div className="ss-muted text-sm mt-1">
              {detail.mergeRequest.sourceInstance.name} <i className="pi pi-arrow-right" style={{ fontSize: '.7rem' }} aria-hidden="true" /> {detail.mergeRequest.targetInstance.name}
            </div>
          </div>
        </div>
        <Tag value={status.replace(/_/g, ' ').toLowerCase()} severity={statusSeverity(status)} />
      </div>

      <Pipeline steps={steps} />

      <div className="mt-3">
        {phase === 'prep' && (
          <div className="surface-card border-round p-4" style={{ border: '1px solid var(--surface-border)' }}>
            <div className="grid">
              <div className="col-12 md:col-6">
                <h3 className="mb-2">Schema compatibility</h3>
                <div className="mb-3 flex gap-2 flex-wrap">
                  <input type="file" accept="application/json" style={{ display: 'none' }} ref={schemaInputRef} onChange={onSchemaFileSelected} />
                  <Button label="Import schema JSON" icon="pi pi-upload" outlined size="small" loading={importSchemaMut.isPending} onClick={() => schemaInputRef.current?.click()} />
                  <input type="file" accept="application/json" style={{ display: 'none' }} ref={prefetchInputRef} onChange={onPrefetchFileSelected} />
                  <Button label="Import prefetch JSON" icon="pi pi-upload" outlined size="small" loading={importPrefetchMut.isPending} onClick={() => prefetchInputRef.current?.click()} />
                </div>
                <SchemaCompatibilityStep schemaCompatible={detail.isCompatible === true} checkingSchema={checkSchemaMut.isPending} checkSchemaCompatibility={checkSchemaCompatibility} />
              </div>
              <div className="col-12 md:col-6">
                <h3 className="mb-2">Content comparison</h3>
                <ContentComparisonStep comparingContent={compareMut.isPending} schemaCompatible={detail.isCompatible === true} compareContent={compareContent} status={status} />
              </div>
            </div>
          </div>
        )}

        {phase === 'merge' && (
          <>
            <div className="surface-card border-round p-3" style={{ border: '1px solid var(--surface-border)' }}>
              {activeStep === 0 && (
                <MergeFilesStep mergeRequestId={detail.mergeRequest.id} filesData={data?.files} loading={false}
                  updateAllSelections={updateAllSelections} selections={data?.selections || []} allMergeData={data!} onSaved={patchMergeData} />
              )}
              {activeStep === 1 && data && (
                <MergeContentWorkspace mergeRequestId={idNum} data={data}
                  updateAllSelections={updateAllSelections} onSaved={() => { refetch(); }} />
              )}
            </div>

            <div className="ss-actionbar">
              <Button label="Re-compare" icon="pi pi-refresh" text size="small" loading={compareMut.isPending} onClick={compareContent} />
              <div className="flex gap-2">
                <Button label="Back" icon="pi pi-arrow-left" outlined disabled={completed || activeStep === 0} onClick={() => setActiveStep(activeStep - 1)} />
                <Button
                  label={activeStep === 1 ? 'Go to review' : 'Next'} icon="pi pi-arrow-right" iconPos="right" disabled={completed}
                  onClick={() => proceedToNextStep(activeStep === 0 ? 1 : 4).catch((e) => showError(apiErrorMessage(e)))}
                />
              </div>
            </div>
          </>
        )}

        {phase === 'review' && (
          <>
            <SnapshotManager mergeRequestId={idNum} onRestoreComplete={() => refetch()} />
            <div className="surface-card border-round p-3 mt-3" style={{ border: '1px solid var(--surface-border)' }}>
              <CompleteMergeStep status={status} completing={completeMut.isPending} completeMerge={completeMerge}
                selections={data?.selections} allMergeData={data} />
            </div>
            {!completed && (
              <div className="mt-3">
                <Button label="Back to wizard" icon="pi pi-arrow-left" outlined onClick={() => { setActiveStep(1); updateMergeRequestStatus('MERGED_COLLECTIONS'); }} />
              </div>
            )}
          </>
        )}
      </div>

      <IdentityReconciliationDialog mergeRequestId={idNum} visible={showReconcile} onHide={() => setShowReconcile(false)} />
    </div>
  );
};

export default MergeRequestDetails;
