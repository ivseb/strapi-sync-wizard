import React, { useState } from 'react';

/**
 * Human-readable, recursive diff between a source entry (incoming) and a target entry (current).
 * Handles nested objects / components / dynamic zones / repeatable arrays at arbitrary depth.
 * Semantics: we bring SOURCE into TARGET.
 *   - create  (no target): every source value is "new" (green)
 *   - update  (both):      changed leaves show current (target) → new (source); unchanged hidden by default
 *   - delete  (no source): every target value is "removed" (red)
 * A raw JSON view is offered separately by the caller for power users.
 */
type Mode = 'create' | 'update' | 'delete';

const TECH = new Set([
  'id', 'documentId', 'document_id', '__order', '__sync_id', 'locale',
  'createdAt', 'updatedAt', 'publishedAt', 'created_at', 'updated_at', 'published_at',
  'created_by_id', 'updated_by_id', 'createdBy', 'updatedBy',
]);

const isObj = (v: any): boolean => v !== null && typeof v === 'object';
const eq = (a: any, b: any): boolean => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

const fmtLeaf = (v: any): string => {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'string') return v.length > 240 ? v.slice(0, 240) + '…' : v;
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  return String(v);
};

const keysOf = (o: any): string[] => (isObj(o) && !Array.isArray(o) ? Object.keys(o).filter((k) => !TECH.has(k)) : []);

const hasChange = (src: any, tgt: any, mode: Mode): boolean => {
  if (mode !== 'update') return true;
  return !eq(src, tgt);
};

const Pill: React.FC<{ kind: 'new' | 'old' | 'same'; children: React.ReactNode }> = ({ kind, children }) => {
  const style: React.CSSProperties =
    kind === 'new' ? { background: 'var(--green-900, #0f3d2e)', color: 'var(--green-200, #86efac)', borderRadius: 4, padding: '1px 6px' }
      : kind === 'old' ? { color: 'var(--text-color-secondary)', textDecoration: 'line-through' }
        : { color: 'var(--text-color-secondary)' };
  return <span style={style}>{children}</span>;
};

const Row: React.FC<{ depth: number; children: React.ReactNode; onClick?: () => void }> = ({ depth, children, onClick }) => (
  <div
    onClick={onClick}
    className="flex align-items-start"
    style={{ padding: '5px 8px', paddingLeft: 8 + depth * 16, borderTop: '1px solid var(--surface-border)', cursor: onClick ? 'pointer' : 'default', gap: 8 }}
  >
    {children}
  </div>
);

const Leaf: React.FC<{ name: string; src: any; tgt: any; mode: Mode; depth: number }> = ({ name, src, tgt, mode, depth }) => {
  const changed = mode === 'update' ? !eq(src, tgt) : true;
  return (
    <Row depth={depth}>
      <span style={{ width: 150, flex: 'none', color: 'var(--text-color-secondary)', fontSize: 13 }}>{name}</span>
      <span style={{ flex: 1, minWidth: 0, fontSize: 13 }}>
        {mode === 'delete' ? (
          <Pill kind="old">{fmtLeaf(tgt)}</Pill>
        ) : mode === 'create' ? (
          <Pill kind="new">{fmtLeaf(src)}</Pill>
        ) : changed ? (
          <span className="flex align-items-center gap-2 flex-wrap">
            <Pill kind="old">{fmtLeaf(tgt)}</Pill>
            <i className="pi pi-arrow-right" style={{ fontSize: '.7rem', color: 'var(--text-color-secondary)' }} aria-hidden="true" />
            <Pill kind="new">{fmtLeaf(src)}</Pill>
          </span>
        ) : (
          <Pill kind="same">{fmtLeaf(src)}</Pill>
        )}
      </span>
    </Row>
  );
};

const Node: React.FC<{ name: string; src: any; tgt: any; mode: Mode; depth: number; showUnchanged: boolean }> = ({ name, src, tgt, mode, depth, showUnchanged }) => {
  const value = mode === 'delete' ? tgt : src;
  const changed = hasChange(src, tgt, mode);
  const [open, setOpen] = useState(mode !== 'update' || changed);

  if (mode === 'update' && !changed && !showUnchanged) return null;

  // Arrays: components / relations / repeatable — compare element-by-element by index.
  if (Array.isArray(value)) {
    const sArr: any[] = Array.isArray(src) ? src : [];
    const tArr: any[] = Array.isArray(tgt) ? tgt : [];
    const len = Math.max(sArr.length, tArr.length);
    return (
      <>
        <Row depth={depth} onClick={() => setOpen(!open)}>
          <i className={`pi pi-chevron-${open ? 'down' : 'right'}`} style={{ fontSize: '.7rem', color: 'var(--text-color-secondary)' }} aria-hidden="true" />
          <span style={{ fontSize: 13 }}>{name}</span>
          <span style={{ color: 'var(--text-color-secondary)', fontSize: 12 }}>[{len}]</span>
        </Row>
        {open && Array.from({ length: len }).map((_, i) => {
          const si = sArr[i]; const ti = tArr[i];
          const itemMode: Mode = si === undefined ? 'delete' : ti === undefined ? 'create' : 'update';
          if (isObj(si ?? ti)) return <Node key={i} name={`#${i + 1}`} src={si} tgt={ti} mode={itemMode} depth={depth + 1} showUnchanged={showUnchanged} />;
          return <Leaf key={i} name={`#${i + 1}`} src={si} tgt={ti} mode={itemMode} depth={depth + 1} />;
        })}
      </>
    );
  }

  // Objects / components
  if (isObj(value)) {
    const allKeys = Array.from(new Set([...keysOf(src), ...keysOf(tgt)]));
    return (
      <>
        <Row depth={depth} onClick={() => setOpen(!open)}>
          <i className={`pi pi-chevron-${open ? 'down' : 'right'}`} style={{ fontSize: '.7rem', color: 'var(--text-color-secondary)' }} aria-hidden="true" />
          <span style={{ fontSize: 13 }}>{name}</span>
        </Row>
        {open && allKeys.map((k) => {
          const sv = isObj(src) ? src[k] : undefined; const tv = isObj(tgt) ? tgt[k] : undefined;
          const childMode: Mode = mode !== 'update' ? mode : sv === undefined ? 'delete' : tv === undefined ? 'create' : 'update';
          if (isObj(sv ?? tv)) return <Node key={k} name={k} src={sv} tgt={tv} mode={childMode} depth={depth + 1} showUnchanged={showUnchanged} />;
          return <Leaf key={k} name={k} src={sv} tgt={tv} mode={childMode} depth={depth + 1} />;
        })}
      </>
    );
  }

  return <Leaf name={name} src={src} tgt={tgt} mode={mode} depth={depth} />;
};

const FieldDiff: React.FC<{ source?: any; target?: any }> = ({ source, target }) => {
  const [showUnchanged, setShowUnchanged] = useState(false);
  const mode: Mode = !target ? 'create' : !source ? 'delete' : 'update';
  const allKeys = Array.from(new Set([...keysOf(source), ...keysOf(target)]));

  const legend =
    mode === 'create' ? { label: 'Will be created', color: 'var(--green-400, #4ade80)' }
      : mode === 'delete' ? { label: 'Will be deleted', color: 'var(--red-400, #f87171)' }
        : { label: 'Will be updated', color: 'var(--yellow-400, #facc15)' };

  return (
    <div>
      <div className="flex align-items-center justify-content-between mb-2">
        <span style={{ color: legend.color, fontSize: 13 }}><i className="pi pi-circle-fill" style={{ fontSize: '.6rem' }} aria-hidden="true" /> {legend.label}</span>
        {mode === 'update' && (
          <label className="flex align-items-center gap-2" style={{ fontSize: 12, color: 'var(--text-color-secondary)', cursor: 'pointer' }}>
            <input type="checkbox" checked={showUnchanged} onChange={(e) => setShowUnchanged(e.target.checked)} />
            Show unchanged fields
          </label>
        )}
      </div>
      <div className="border-round" style={{ border: '1px solid var(--surface-border)', overflow: 'hidden' }}>
        {allKeys.length === 0 ? (
          <div className="p-3 ss-muted text-sm">No fields to show.</div>
        ) : (
          allKeys.map((k) => {
            const sv = isObj(source) ? source[k] : undefined; const tv = isObj(target) ? target[k] : undefined;
            const childMode: Mode = mode !== 'update' ? mode : sv === undefined ? 'delete' : tv === undefined ? 'create' : 'update';
            if (isObj(sv ?? tv)) return <Node key={k} name={k} src={sv} tgt={tv} mode={childMode} depth={0} showUnchanged={showUnchanged} />;
            return <Leaf key={k} name={k} src={sv} tgt={tv} mode={childMode} depth={0} />;
          })
        )}
      </div>
    </div>
  );
};

export default FieldDiff;
