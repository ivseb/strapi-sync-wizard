import React, { useMemo, useState } from 'react';
import { Dialog } from 'primereact/dialog';

/**
 * Side-by-side (source | target) recursive diff. Flattens nested objects /
 * components / dynamic zones / arrays into dotted leaf paths and shows each
 * changed field with the incoming SOURCE value next to the current TARGET value.
 * Semantics: we bring SOURCE into TARGET (source = incoming, target = current).
 */

// Cross-environment identifiers / relation pointers: these differ between instances even when the
// content is identical (different documentId per instance, relations referenced by documentId).
// They are not "content changes", so they're excluded from the field diff and surfaced separately.
const IDENTITY = new Set(['id', 'documentId', 'document_id', '__sync_id', '__links']);
const TECH = new Set([
  ...IDENTITY,
  '__order', '__component', 'locale',
  'createdAt', 'updatedAt', 'publishedAt', 'created_at', 'updated_at', 'published_at',
  'created_by_id', 'updated_by_id', 'createdBy', 'updatedBy',
]);

const isObj = (v: any): boolean => v !== null && typeof v === 'object';
const eq = (a: any, b: any): boolean => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
const keysOf = (o: any): string[] => (isObj(o) && !Array.isArray(o) ? Object.keys(o).filter((k) => !TECH.has(k)) : []);

const isMedia = (v: any): boolean =>
  isObj(v) && !Array.isArray(v) && (typeof v.url === 'string' || typeof v.mime === 'string' || typeof v.ext === 'string');

const fmt = (v: any): string => {
  if (v === null || v === undefined || v === '') return '—';
  if (isMedia(v)) return v.name || v.url || 'file';
  if (typeof v === 'string') return v;
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (isObj(v)) {
    try {
      const clean = Array.isArray(v) ? v : Object.fromEntries(Object.entries(v).filter(([k]) => !TECH.has(k)));
      if (!Array.isArray(clean) && Object.keys(clean).length === 0) return '—';
      return JSON.stringify(clean);
    } catch { return String(v); }
  }
  return String(v);
};

export interface Leaf { path: string; src: any; tgt: any; changed: boolean }

const flatten = (path: string, src: any, tgt: any, out: Leaf[]): void => {
  if (src === undefined && tgt === undefined) return;
  const leafy = (!isObj(src) && !isObj(tgt)) || isMedia(src) || isMedia(tgt);
  if (leafy) { out.push({ path, src, tgt, changed: !eq(src, tgt) }); return; }

  if (Array.isArray(src) || Array.isArray(tgt)) {
    const a: any[] = Array.isArray(src) ? src : [];
    const b: any[] = Array.isArray(tgt) ? tgt : [];
    const len = Math.max(a.length, b.length);
    if (len === 0) { out.push({ path, src, tgt, changed: !eq(src, tgt) }); return; }
    for (let i = 0; i < len; i++) flatten(`${path}[${i + 1}]`, a[i], b[i], out);
    return;
  }

  const keys = Array.from(new Set([...keysOf(src), ...keysOf(tgt)]));
  if (keys.length === 0) { out.push({ path, src, tgt, changed: !eq(src, tgt) }); return; }
  keys.forEach((k) => flatten(path ? `${path}.${k}` : k, isObj(src) ? src[k] : undefined, isObj(tgt) ? tgt[k] : undefined, out));
};

export const computeLeaves = (source: any, target: any): Leaf[] => {
  const out: Leaf[] = [];
  flatten('', source, target, out);
  return out;
};

/** True when the entry is flagged different but the only differences are cross-env ids/relations. */
export const identityOnlyDiff = (source: any, target: any): boolean => {
  if (!source || !target) return false;
  const contentChanged = computeLeaves(source, target).some((l) => l.changed);
  if (contentChanged) return false;
  const linksDiff = !eq(source.__links, target.__links);
  const docDiff = (source.document_id ?? source.documentId) !== (target.document_id ?? target.documentId);
  return linksDiff || docDiff;
};

/** Top-level changed field names, for a one-line summary. */
export const changeSummary = (source: any, target: any, max = 4): string => {
  const leaves = computeLeaves(source, target).filter((l) => l.changed);
  const tops: string[] = [];
  leaves.forEach((l) => {
    const top = l.path.split(/[.[]/)[0];
    const added = l.src !== undefined && l.tgt === undefined;
    const label = added ? `+${top}` : top;
    if (!tops.includes(label)) tops.push(label);
  });
  if (tops.length === 0) return identityOnlyDiff(source, target) ? 'only relations/ids differ' : 'no field changes';
  return tops.slice(0, max).join(' · ') + (tops.length > max ? ` +${tops.length - max}` : '');
};

// ---- word-level inline diff for strings (with char-level refinement) ----
type Seg = { t: string; k: 'eq' | 'add' | 'del' };
type Op = { k: 'eq' | 'del' | 'add'; t: string };
const tokenize = (s: string): string[] => s.split(/(\s+)/).filter((x) => x !== '');

// Generic ordered LCS diff over a sequence (used for both words and characters).
const diffOps = (o: string[], n: string[]): Op[] => {
  const dp: number[][] = Array.from({ length: o.length + 1 }, () => new Array(n.length + 1).fill(0));
  for (let i = o.length - 1; i >= 0; i--)
    for (let j = n.length - 1; j >= 0; j--)
      dp[i][j] = o[i] === n[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const ops: Op[] = [];
  let i = 0, j = 0;
  while (i < o.length && j < n.length) {
    if (o[i] === n[j]) { ops.push({ k: 'eq', t: o[i] }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { ops.push({ k: 'del', t: o[i] }); i++; }
    else { ops.push({ k: 'add', t: n[j] }); j++; }
  }
  while (i < o.length) ops.push({ k: 'del', t: o[i++] });
  while (j < n.length) ops.push({ k: 'add', t: n[j++] });
  return ops;
};

const pushSeg = (arr: Seg[], k: Seg['k'], t: string): void => {
  const last = arr[arr.length - 1];
  if (last && last.k === k) last.t += t; else arr.push({ t, k });
};

// Char-level segments for a replaced chunk. Returns null when the two chunks are too dissimilar,
// so we keep a clean whole-word strike/insert instead of confetti highlighting.
const charRefine = (delStr: string, addStr: string): { oSeg: Seg[]; nSeg: Seg[] } | null => {
  const ops = diffOps([...delStr], [...addStr]);
  const eqChars = ops.reduce((s, o) => s + (o.k === 'eq' ? o.t.length : 0), 0);
  if (eqChars / Math.max(delStr.length, addStr.length, 1) < 0.4) return null;
  const oSeg: Seg[] = [], nSeg: Seg[] = [];
  ops.forEach((op) => {
    if (op.k === 'eq') { pushSeg(oSeg, 'eq', op.t); pushSeg(nSeg, 'eq', op.t); }
    else if (op.k === 'del') pushSeg(oSeg, 'del', op.t);
    else pushSeg(nSeg, 'add', op.t);
  });
  return { oSeg, nSeg };
};

const nonWsLen = (s: string): number => s.replace(/\s+/g, '').length;

const wordDiff = (oldS: string, newS: string): { oSeg: Seg[]; nSeg: Seg[] } => {
  const oTok = tokenize(oldS), nTok = tokenize(newS);
  const ops = diffOps(oTok, nTok);

  // When the two values barely overlap, aligning on incidental shared words ("richiesta.", "di", …)
  // fragments the diff into noise. Show a clean wholesale replacement instead.
  const eqW = ops.reduce((a, o) => a + (o.k === 'eq' ? nonWsLen(o.t) : 0), 0);
  const oW = oTok.reduce((a, t) => a + nonWsLen(t), 0);
  const nW = nTok.reduce((a, t) => a + nonWsLen(t), 0);
  if (eqW / Math.max(oW, nW, 1) < 0.25) {
    const oSeg: Seg[] = [], nSeg: Seg[] = [];
    oTok.forEach((t) => pushSeg(oSeg, 'del', t));
    nTok.forEach((t) => pushSeg(nSeg, 'add', t));
    return { oSeg, nSeg };
  }

  const oSeg: Seg[] = [], nSeg: Seg[] = [];
  let p = 0;
  while (p < ops.length) {
    if (ops[p].k === 'eq') { pushSeg(oSeg, 'eq', ops[p].t); pushSeg(nSeg, 'eq', ops[p].t); p++; continue; }
    // Collect a contiguous run of replacements (dels on the old side, adds on the new side).
    let q = p; const dels: string[] = [], adds: string[] = [];
    while (q < ops.length && ops[q].k !== 'eq') { if (ops[q].k === 'del') dels.push(ops[q].t); else adds.push(ops[q].t); q++; }
    const delStr = dels.join(''), addStr = adds.join('');
    const refined = (delStr && addStr) ? charRefine(delStr, addStr) : null;
    if (refined) {
      refined.oSeg.forEach((s) => pushSeg(oSeg, s.k, s.t));
      refined.nSeg.forEach((s) => pushSeg(nSeg, s.k, s.t));
    } else {
      dels.forEach((t) => pushSeg(oSeg, 'del', t));
      adds.forEach((t) => pushSeg(nSeg, 'add', t));
    }
    p = q;
  }
  return { oSeg, nSeg };
};

const segStyle = (k: Seg['k']): React.CSSProperties =>
  k === 'add' ? { background: 'var(--ss-add-bg)', color: 'var(--ss-add-fg)', borderRadius: 3 }
    : k === 'del' ? { background: 'var(--ss-del-bg)', color: 'var(--ss-del-fg)', borderRadius: 3 }
      : {};

// Make a CHANGED whitespace-only token visible (otherwise a trailing space / newline diff looks
// like "no difference").
const visibleWs = (t: string): string => t.replace(/\n/g, '⏎\n').replace(/\t/g, '⇥').replace(/ /g, '·');
const Segments: React.FC<{ segs: Seg[] }> = ({ segs }) => (
  <>{segs.map((s, i) => {
    if (s.k === 'eq') return <span key={i}>{s.t}</span>;
    const ws = /^\s+$/.test(s.t);
    return <span key={i} style={segStyle(s.k)}>{ws ? visibleWs(s.t) : s.t}</span>;
  })}</>
);

const valBox: React.CSSProperties = { whiteSpace: 'pre-wrap', maxHeight: 320, overflowY: 'auto', wordBreak: 'break-word' };

// A backend-resolved reference target: file (thumbnail) or content entry (label). syncId is the
// cross-instance identity used to decide whether a reference actually changed.
export interface ResolvedRefT { documentId: string; label: string; syncId?: string | null; isFile?: boolean; fileId?: number | null; mime?: string | null; contentHash?: string | null; refType?: string | null }

// Identity of a referenced target (syncId, else content/byte hash, else label). contentHash already
// carries the backend-computed identity ("id:…"/"c:…"/"file:…"); fall back for safety.
const refKey = (r: ResolvedRefT): string => r.contentHash || (r.syncId ? `id:${r.syncId}` : `lbl:${r.label}`);

const RefChip: React.FC<{ r: ResolvedRefT; instanceId?: number; onZoom?: (url: string, label: string) => void; onOpenRef?: (documentId: string, label: string) => void }> = ({ r, instanceId, onZoom, onOpenRef }) => {
  const thumb = r.isFile && r.fileId && instanceId ? `/api/instances/${instanceId}/media/file/raw?fileId=${r.fileId}` : '';
  const isImg = (r.mime || '').startsWith('image') && !!thumb;
  const openable = !r.isFile && !!onOpenRef;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, margin: '3px 10px 3px 0', maxWidth: '100%' }} title={r.syncId ? r.documentId : 'not linked across instances yet'}>
      {r.isFile ? (
        <span onClick={isImg ? () => onZoom?.(thumb, r.label) : undefined}
          style={{ width: 32, height: 24, borderRadius: 4, background: '#f3f4f6', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', flex: 'none', cursor: isImg ? 'zoom-in' : 'default' }}>
          {isImg
            ? <img src={thumb} alt="" style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
            : <i className={(r.mime === 'application/pdf') ? 'pi pi-file-pdf' : 'pi pi-file'} style={{ fontSize: 12, color: '#6b7280' }} aria-hidden="true" />}
        </span>
      ) : (
        <i className="pi pi-link" style={{ fontSize: 12, color: 'var(--ss-text-3)' }} aria-hidden="true" />
      )}
      {openable ? (
        <button className="ss-link" onClick={() => onOpenRef!(r.documentId, r.label)}
          style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 220, padding: 0 }} title="Open detail">{r.label}</button>
      ) : (
        <span style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 220 }}>{r.label}</span>
      )}
      {r.refType && <span className="ss-badge" style={{ fontSize: 9 }}>{r.refType}</span>}
      {!r.syncId && <i className="pi pi-exclamation-triangle" style={{ fontSize: 10, color: 'var(--ss-amber)' }} aria-hidden="true" title="not linked across instances yet" />}
    </span>
  );
};

const Cell: React.FC<{ v: any; kind: 'src' | 'tgt'; changed: boolean }> = ({ v, kind, changed }) => {
  const defined = v !== undefined;
  const cls = !defined ? 'empty' : changed ? (kind === 'src' ? 'add' : 'del') : '';
  return (
    <div className={`c ${cls}`} style={valBox}>
      {!defined ? '—' : isMedia(v) ? (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span className="ss-thumb">{v.url ? <img src={v.url} alt="" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} /> : <i className="pi pi-image" aria-hidden="true" />}</span>
          {fmt(v)}
        </span>
      ) : fmt(v)}
    </div>
  );
};

const LeafRow: React.FC<{ leaf: Leaf }> = ({ leaf }) => {
  const bothStrings = typeof leaf.src === 'string' && typeof leaf.tgt === 'string';
  if (leaf.changed && bothStrings) {
    const { oSeg, nSeg } = wordDiff(leaf.tgt as string, leaf.src as string);
    return (
      <>
        <div className="c k">{leaf.path || '(root)'}</div>
        <div className="c" style={valBox}><Segments segs={nSeg} /></div>
        <div className="c" style={valBox}><Segments segs={oSeg} /></div>
      </>
    );
  }
  return (
    <>
      <div className="c k">{leaf.path || '(root)'}</div>
      <Cell v={leaf.src} kind="src" changed={leaf.changed} />
      <Cell v={leaf.tgt} kind="tgt" changed={leaf.changed} />
    </>
  );
};

const FieldDiffSplit: React.FC<{
  source?: any; target?: any;
  sourceRefs?: Record<string, ResolvedRefT[]>; targetRefs?: Record<string, ResolvedRefT[]>;
  srcInstanceId?: number; tgtInstanceId?: number;
  onOpenRef?: (documentId: string, label: string) => void;
}> = ({ source, target, sourceRefs, targetRefs, srcInstanceId, tgtInstanceId, onOpenRef }) => {
  const [showUnchanged, setShowUnchanged] = useState(false);
  const [zoom, setZoom] = useState<{ url: string; label: string } | null>(null);
  const leaves = useMemo(() => computeLeaves(source, target), [source, target]);
  const shown = showUnchanged ? leaves : leaves.filter((l) => l.changed);

  // Backend already resolved __links to readable, identity-bearing references (field -> refs) per
  // side. A field is "changed" when the cross-instance identity (syncId, else label) lists differ:
  // the SAME logical target with a different per-instance documentId is NOT a change.
  const linkRows = useMemo(() => {
    const sl = sourceRefs || {};
    const tl = targetRefs || {};
    const fields = Array.from(new Set([...Object.keys(sl), ...Object.keys(tl)]));
    return fields.map((field) => {
      const sRefs = sl[field] || [];
      const tRefs = tl[field] || [];
      // order-insensitive (sorted) — reordering duplicate/identical targets is not a change.
      const changed = sRefs.map(refKey).sort().join('|') !== tRefs.map(refKey).sort().join('|');
      return { field, sRefs, tRefs, changed };
    });
  }, [sourceRefs, targetRefs]);
  const changedLinks = linkRows.filter((l) => l.changed);
  const shownLinks = showUnchanged ? linkRows : changedLinks;

  return (
    <div>
      {shown.length > 0 && (
        <div className="ss-diff">
          <div className="h">field</div>
          <div className="h src">source (incoming)</div>
          <div className="h">target (current)</div>
          {shown.map((l, i) => <LeafRow key={l.path + i} leaf={l} />)}
        </div>
      )}

      {shownLinks.length > 0 && (
        <div className="ss-diff" style={{ marginTop: shown.length > 0 ? 8 : 0 }}>
          <div className="h">reference</div>
          <div className="h src">source (incoming)</div>
          <div className="h">target (current)</div>
          {shownLinks.map((l, i) => (
            <React.Fragment key={'lnk' + i}>
              <div className="c k">{l.field}</div>
              <div className={`c ${l.changed ? 'add' : ''}`}>{l.sRefs.length ? l.sRefs.map((r, j) => <RefChip key={j} r={r} instanceId={srcInstanceId} onZoom={(url, label) => setZoom({ url, label })} onOpenRef={onOpenRef} />) : '—'}</div>
              <div className={`c ${l.changed ? 'del' : ''}`}>{l.tRefs.length ? l.tRefs.map((r, j) => <RefChip key={j} r={r} instanceId={tgtInstanceId} onZoom={(url, label) => setZoom({ url, label })} onOpenRef={onOpenRef} />) : '—'}</div>
            </React.Fragment>
          ))}
        </div>
      )}

      {shown.length === 0 && changedLinks.length === 0 && (
        <div className="ss-diff">
          <div className="c" style={{ gridColumn: '1 / -1', color: 'var(--ss-text-3)' }}>No field changes.</div>
        </div>
      )}

      <label className="ss-link" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginTop: 8, cursor: 'pointer' }}>
        <input type="checkbox" checked={showUnchanged} onChange={(e) => setShowUnchanged(e.target.checked)} />
        Show unchanged fields & references
      </label>

      <Dialog header={zoom?.label} visible={zoom != null} style={{ width: 'auto', maxWidth: '90vw' }} onHide={() => setZoom(null)} dismissableMask>
        {zoom && (
          <div style={{ background: '#f3f4f6', borderRadius: 8, padding: 12, display: 'flex', justifyContent: 'center' }}>
            <img src={zoom.url} alt={zoom.label} style={{ maxWidth: '80vw', maxHeight: '78vh', objectFit: 'contain' }} />
          </div>
        )}
      </Dialog>
    </div>
  );
};

export default FieldDiffSplit;
