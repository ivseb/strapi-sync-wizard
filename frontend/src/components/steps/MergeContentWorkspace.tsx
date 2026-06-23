import React, { useMemo, useState } from 'react';
import { Button } from 'primereact/button';
import { Tag } from 'primereact/tag';
import { InputText } from 'primereact/inputtext';
import { Checkbox } from 'primereact/checkbox';
import {
  ContentTypeComparisonResultKind,
  ContentTypeComparisonResultWithRelationships,
  MergeRequestData,
  MergeRequestSelectionDTO,
  StrapiContentTypeKind,
} from '../../types';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import FieldDiff from '../common/FieldDiff';
import ExclusionsManager from './components/ExclusionsManager';
import ManualCollectionMapper from './components/ManualCollectionMapper';

interface Props {
  mergeRequestId: number;
  data: MergeRequestData;
  updateAllSelections: (
    kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string,
    documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind
  ) => Promise<boolean>;
  onSaved?: () => void | Promise<void>;
}

interface ChangeRow {
  uid: string;
  tableName: string;
  kind: StrapiContentTypeKind;
  documentId: string;
  label: string;
  state: ContentTypeComparisonResultKind;
  syncId?: string | null;
  source?: any;
  target?: any;
}

const STATE_META: Record<string, { label: string; severity: 'success' | 'warning' | 'info' | 'danger' }> = {
  ONLY_IN_SOURCE: { label: 'to create', severity: 'success' },
  DIFFERENT: { label: 'changed', severity: 'warning' },
  ONLY_IN_TARGET: { label: 'only in target', severity: 'info' },
  IDENTICAL: { label: 'identical', severity: 'info' },
  EXCLUDED: { label: 'excluded', severity: 'danger' },
};

const labelOf = (r: ContentTypeComparisonResultWithRelationships): string => {
  const c = r.sourceContent || r.targetContent;
  if (!c) return '(empty)';
  const attrs = getRepresentativeAttributes(c as any, 2);
  return attrs.length ? attrs.map((a) => a.value).join(' · ') : (c.metadata?.documentId || '(entry)');
};

const MergeContentWorkspace: React.FC<Props> = ({ mergeRequestId, data, updateAllSelections, onSaved }) => {
  const [search, setSearch] = useState('');
  const [showIdentical, setShowIdentical] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [activeStates, setActiveStates] = useState<Set<string>>(new Set(['ONLY_IN_SOURCE', 'DIFFERENT', 'ONLY_IN_TARGET']));
  const [showExclusions, setShowExclusions] = useState(false);
  const [mapperTable, setMapperTable] = useState<string | null>(null);

  const selectedSet = useMemo(() => {
    const s = new Set<string>();
    (data.selections || ([] as MergeRequestSelectionDTO[])).forEach((sel) => {
      (sel.selections || []).forEach((x: any) => s.add(`${sel.tableName}:${x.documentId}`));
    });
    return s;
  }, [data.selections]);

  const groups = useMemo(() => {
    const map = new Map<string, { uid: string; tableName: string; kind: StrapiContentTypeKind; displayName: string; rows: ChangeRow[] }>();
    const push = (r: ContentTypeComparisonResultWithRelationships, kind: StrapiContentTypeKind) => {
      const c = r.sourceContent || r.targetContent;
      const documentId = c?.metadata?.documentId || r.id;
      const row: ChangeRow = {
        uid: r.contentType, tableName: r.tableName, kind, documentId,
        label: labelOf(r), state: r.compareState,
        syncId: r.sourceContent?.metadata?.syncId || r.targetContent?.metadata?.syncId || null,
        source: r.sourceContent?.cleanData, target: r.targetContent?.cleanData,
      };
      const g = map.get(r.contentType) || { uid: r.contentType, tableName: r.tableName, kind, displayName: r.contentType.split('.').pop() || r.contentType, rows: [] };
      g.rows.push(row);
      map.set(r.contentType, g);
    };
    Object.values(data.singleTypes || {}).forEach((r) => push(r, StrapiContentTypeKind.SingleType));
    Object.values(data.collectionTypes || {}).forEach((arr) => (arr || []).forEach((r) => push(r, StrapiContentTypeKind.CollectionType)));
    return Array.from(map.values());
  }, [data.singleTypes, data.collectionTypes]);

  const visibleGroups = useMemo(() => {
    const q = search.trim().toLowerCase();
    return groups
      .map((g) => ({
        ...g,
        rows: g.rows.filter((r) => {
          if (r.state === 'IDENTICAL' && !showIdentical) return false;
          if (r.state !== 'IDENTICAL' && !activeStates.has(r.state)) return false;
          if (q && !(`${r.label} ${r.documentId}`.toLowerCase().includes(q))) return false;
          return true;
        }),
      }))
      .filter((g) => g.rows.length > 0);
  }, [groups, search, showIdentical, activeStates]);

  const counts = useMemo(() => {
    const c: Record<string, number> = { ONLY_IN_SOURCE: 0, DIFFERENT: 0, ONLY_IN_TARGET: 0, IDENTICAL: 0 };
    groups.forEach((g) => g.rows.forEach((r) => { c[r.state] = (c[r.state] || 0) + 1; }));
    return c;
  }, [groups]);

  const toggleState = (s: string) => {
    setActiveStates((prev) => {
      const n = new Set(prev);
      n.has(s) ? n.delete(s) : n.add(s);
      return n;
    });
  };

  const toggleRow = async (r: ChangeRow, selected: boolean) => {
    await updateAllSelections(r.kind, selected, r.tableName, [r.documentId]);
  };

  const toggleGroup = async (g: { kind: StrapiContentTypeKind; tableName: string; rows: ChangeRow[] }, selected: boolean) => {
    const ids = g.rows.filter((r) => r.state !== 'IDENTICAL').map((r) => r.documentId);
    if (ids.length) await updateAllSelections(g.kind, selected, g.tableName, ids);
  };

  return (
    <div>
      <div className="flex flex-wrap align-items-center gap-2 mb-3">
        <span className="p-input-icon-left">
          <i className="pi pi-search" />
          <InputText value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search entries..." className="w-18rem" />
        </span>
        {(['ONLY_IN_SOURCE', 'DIFFERENT', 'ONLY_IN_TARGET'] as const).map((s) => (
          <button key={s} type="button" onClick={() => toggleState(s)}
            className="border-round"
            style={{
              cursor: 'pointer', fontSize: 12, padding: '5px 10px', border: '1px solid var(--surface-border)',
              background: activeStates.has(s) ? 'var(--primary-color)' : 'transparent',
              color: activeStates.has(s) ? 'var(--primary-color-text)' : 'var(--text-color-secondary)',
            }}>
            {STATE_META[s].label} · {counts[s] || 0}
          </button>
        ))}
        <label className="flex align-items-center gap-2 ss-muted" style={{ fontSize: 12, cursor: 'pointer' }}>
          <Checkbox checked={showIdentical} onChange={(e) => setShowIdentical(!!e.checked)} /> show identical ({counts.IDENTICAL || 0})
        </label>
        <span className="flex-1" />
        <Button label="Manage exclusions" icon="pi pi-ban" outlined size="small" onClick={() => setShowExclusions(true)} />
      </div>

      {visibleGroups.length === 0 ? (
        <div className="surface-card border-round p-4 ss-muted" style={{ border: '1px solid var(--surface-border)' }}>No changes match the current filters.</div>
      ) : (
        <div className="flex flex-column gap-3">
          {visibleGroups.map((g) => {
            const selectable = g.rows.filter((r) => r.state !== 'IDENTICAL');
            const allSelected = selectable.length > 0 && selectable.every((r) => selectedSet.has(`${r.tableName}:${r.documentId}`));
            return (
              <div key={g.uid} className="surface-card border-round" style={{ border: '1px solid var(--surface-border)', overflow: 'hidden' }}>
                <div className="flex align-items-center gap-2 p-3" style={{ borderBottom: '1px solid var(--surface-border)' }}>
                  <Checkbox checked={allSelected} onChange={(e) => toggleGroup(g, !!e.checked)} disabled={selectable.length === 0} />
                  <span style={{ fontWeight: 500 }}>{g.displayName}</span>
                  <span className="ss-muted text-sm">{g.kind === StrapiContentTypeKind.SingleType ? 'single type' : 'collection'} · {g.rows.length}</span>
                  {g.kind === StrapiContentTypeKind.CollectionType && (
                    <>
                      <span className="flex-1" />
                      <Button label="Manual mapping" icon="pi pi-link" text size="small" onClick={() => setMapperTable(g.tableName)} />
                    </>
                  )}
                </div>
                {g.rows.map((r) => {
                  const key = `${r.tableName}:${r.documentId}`;
                  const isSel = selectedSet.has(key);
                  const isOpen = expanded === key;
                  const meta = STATE_META[r.state] || STATE_META.IDENTICAL;
                  return (
                    <div key={key} style={{ borderTop: '1px solid var(--surface-border)' }}>
                      <div className="flex align-items-center gap-3 p-3">
                        <Checkbox checked={isSel} disabled={r.state === 'IDENTICAL'} onChange={(e) => toggleRow(r, !!e.checked)} />
                        <Tag value={meta.label} severity={meta.severity} />
                        <span style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.label}</span>
                        {r.syncId && <span className="ss-muted text-sm" title="identity linked"><i className="pi pi-link" /> </span>}
                        {(r.source || r.target) && (
                          <Button label={isOpen ? 'Hide diff' : 'View diff'} icon={isOpen ? 'pi pi-chevron-up' : 'pi pi-chevron-down'}
                            text size="small" onClick={() => setExpanded(isOpen ? null : key)} />
                        )}
                      </div>
                      {isOpen && (
                        <div className="px-3 pb-3">
                          <FieldDiff source={r.source} target={r.target} />
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            );
          })}
        </div>
      )}

      <ExclusionsManager
        visible={showExclusions}
        onHide={() => setShowExclusions(false)}
        mergeRequestId={mergeRequestId}
        onExclusionsChanged={() => onSaved && onSaved()}
      />

      {mapperTable && (
        <ManualCollectionMapper
          visible={!!mapperTable}
          onHide={() => setMapperTable(null)}
          mergeRequestId={mergeRequestId}
          collectionTypesData={{ [groups.find((g) => g.tableName === mapperTable)?.uid || mapperTable]: (data.collectionTypes?.[groups.find((g) => g.tableName === mapperTable)?.uid || ''] || []) }}
          allMergeData={data}
          fixedTable={mapperTable}
          onSaved={onSaved}
        />
      )}
    </div>
  );
};

export default MergeContentWorkspace;
