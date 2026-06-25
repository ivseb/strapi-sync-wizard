import React, { useMemo, useState } from 'react';
import { SyncPlanDTO, Direction } from '../../../types';

export interface SyncPlanGraphProps {
    syncPlan: SyncPlanDTO;
    // key: `${table}:${documentId}` -> status
    syncItemsStatus: Record<string, { status: string; message?: string }>;
    onInspect: (tableName: string, documentId: string) => void;
    // Resolve a readable label for an item; falls back to the documentId.
    labelFor?: (tableName: string, documentId: string) => string;
}

type Op = 'create' | 'update' | 'delete';
const opToLabel = (dir: Direction): Op =>
    dir === 'TO_CREATE' ? 'create' : dir === 'TO_UPDATE' ? 'update' : 'delete';

const shortType = (ct: string) => (ct === 'files' ? 'files' : ct.substring(ct.lastIndexOf('.') + 1));

// Dark-theme palette, aligned with the diff tokens.
const opColors: Record<Op, { fill: string; stroke: string; text: string }> = {
    create: { fill: 'var(--ss-add-bg)', stroke: 'var(--ss-green)', text: 'var(--ss-add-fg)' },
    update: { fill: 'var(--ss-amber-bg)', stroke: 'var(--ss-amber)', text: 'var(--ss-amber)' },
    delete: { fill: 'var(--ss-del-bg)', stroke: 'var(--ss-red)', text: 'var(--ss-del-fg)' },
};

const statusIcon = (status?: string): { char: string; color: string; title: string } => {
    switch (status) {
        case 'IN_PROGRESS': return { char: '●', color: 'var(--ss-info)', title: 'In progress' };
        case 'SUCCESS': return { char: '✔', color: 'var(--ss-green)', title: 'Success' };
        case 'ERROR': return { char: '✖', color: 'var(--ss-red)', title: 'Error' };
        default: return { char: '○', color: 'var(--ss-text-3)', title: 'Pending' };
    }
};

const SyncPlanGraph: React.FC<SyncPlanGraphProps> = ({ syncPlan, syncItemsStatus, onInspect, labelFor }) => {
    const [hoveredId, setHoveredId] = useState<string | null>(null);
    const label = (t: string, d: string) => (labelFor ? labelFor(t, d) : d);
    const trunc = (s: string, max = 30) => (s.length > max ? s.slice(0, max - 1) + '…' : s);

    // Flat list of items with batch index (deduped).
    const items = useMemo(() => {
        const list: { table: string; docId: string; op: Op; batch: number }[] = [];
        const seen = new Set<string>();
        (syncPlan.batches || []).forEach((batch, bi) => {
            batch.forEach((it) => {
                const k = `${it.tableName}:${it.documentId}`;
                if (seen.has(k)) return;
                seen.add(k);
                list.push({ table: it.tableName, docId: it.documentId, op: opToLabel(it.direction), batch: bi });
            });
        });
        return list;
    }, [syncPlan]);

    // Group by batch, then by table (one container per table per step).
    const batches = useMemo(() => {
        const res: { index: number; tables: { table: string; entries: { docId: string; op: Op }[] }[] }[] = [];
        const batchCount = (syncPlan.batches || []).length;
        for (let bi = 0; bi < batchCount; bi++) {
            const byTable = new Map<string, { table: string; entries: { docId: string; op: Op }[] }>();
            items.filter((i) => i.batch === bi).forEach((i) => {
                if (!byTable.has(i.table)) byTable.set(i.table, { table: i.table, entries: [] });
                byTable.get(i.table)!.entries.push({ docId: i.docId, op: i.op });
            });
            const tables = Array.from(byTable.values()).sort((a, b) => a.table.localeCompare(b.table));
            if (tables.length) res.push({ index: bi, tables });
        }
        return res;
    }, [items, syncPlan]);

    // Layout constants
    const padding = 20;
    const tableBoxW = 250;
    const tableHeaderH = 26;
    const batchHeaderH = 26;
    const itemH = 26;
    const itemGap = 6;
    const colGap = 64;
    const tableGapY = 16;

    type ItemPos = { x: number; y: number; w: number; h: number };
    const { svgW, svgH, tablePositions, itemPositions, columnRects } = useMemo(() => {
        const tablePositions: Record<string, ItemPos> = {};
        const itemPositions: Record<string, ItemPos> = {};
        const columnRects: { x: number; y: number; w: number; h: number; label: string }[] = [];

        const columns = batches.length;
        const svgW = padding * 2 + Math.max(0, columns) * tableBoxW + Math.max(0, columns - 1) * colGap;

        let maxColumnH = 0;
        for (let bi = 0; bi < columns; bi++) {
            const colX = padding + bi * (tableBoxW + colGap);
            let y = padding + batchHeaderH + 8;
            const tables = batches[bi].tables;
            let colH = batchHeaderH + 8;
            tables.forEach((t) => {
                const itemsCount = t.entries.length;
                const listH = itemsCount * itemH + Math.max(0, itemsCount - 1) * itemGap;
                const h = tableHeaderH + 12 + listH + 12;
                const key = `${bi}::${t.table}`;
                tablePositions[key] = { x: colX, y, w: tableBoxW, h };

                const listX = colX + 12;
                let listY = y + tableHeaderH + 12;
                t.entries.forEach((entry) => {
                    itemPositions[`${t.table}:${entry.docId}`] = { x: listX, y: listY, w: tableBoxW - 24, h: itemH };
                    listY += itemH + itemGap;
                });

                y += h + tableGapY;
                colH += h + tableGapY;
            });
            colH = Math.max(batchHeaderH + 8, colH - tableGapY);
            maxColumnH = Math.max(maxColumnH, colH);
            columnRects.push({ x: colX - 8, y: padding, w: tableBoxW + 16, h: colH + 8, label: `Step ${bi + 1}` });
        }

        const svgH = padding * 2 + maxColumnH;
        return { svgW, svgH, tablePositions, itemPositions, columnRects };
    }, [batches]);

    const allEdges = useMemo(() => {
        const normal = (syncPlan.edges || []).map((e) => ({ from: `${e.fromTable}:${e.fromDocumentId}`, to: `${e.toTable}:${e.toDocumentId}`, circular: false as const }));
        const circular = (syncPlan.circularEdges || []).map((e) => ({ from: `${e.fromTable}:${e.fromDocumentId}`, to: `${e.toTable}:${e.toDocumentId}`, circular: true as const }));
        return [...normal, ...circular].filter((e) => itemPositions[e.from] && itemPositions[e.to]);
    }, [syncPlan, itemPositions]);

    // Orthogonal "smoothstep" routing: horizontal out of the source, a vertical run at lane x `vx`
    // (separated per edge to avoid stacking), horizontal into the target. Rounded corners.
    const pathFor = (sx: number, sy: number, tx: number, ty: number, vx: number): string => {
        const hdir = vx >= sx ? 1 : -1;
        const hdir2 = tx >= vx ? 1 : -1;
        const vdir = ty >= sy ? 1 : -1;
        const r = Math.min(8, Math.abs(ty - sy) / 2, Math.abs(vx - sx), Math.abs(tx - vx));
        if (!isFinite(r) || r < 1 || Math.abs(ty - sy) < 2) return `M ${sx} ${sy} L ${vx} ${sy} L ${vx} ${ty} L ${tx} ${ty}`;
        return [
            `M ${sx} ${sy}`,
            `L ${vx - hdir * r} ${sy}`,
            `Q ${vx} ${sy} ${vx} ${sy + vdir * r}`,
            `L ${vx} ${ty - vdir * r}`,
            `Q ${vx} ${ty} ${vx + hdir2 * r} ${ty}`,
            `L ${tx} ${ty}`,
        ].join(' ');
    };

    // Per-edge geometry with lane separation: edges sharing a vertical corridor get distinct `vx`.
    type EdgeGeom = { from: string; to: string; circular: boolean; sx: number; sy: number; tx: number; ty: number; vx: number };
    const edgeGeoms = useMemo<EdgeGeom[]>(() => {
        const geoms = allEdges.map((e) => {
            const p1 = itemPositions[e.from], p2 = itemPositions[e.to];
            const goRight = (p2.x + p2.w / 2) >= (p1.x + p1.w / 2);
            const sx = goRight ? p1.x + p1.w : p1.x;
            const sy = p1.y + p1.h / 2;
            const tx = goRight ? p2.x : p2.x + p2.w;
            const ty = p2.y + p2.h / 2;
            const base = (sx + tx) / 2;
            return { from: e.from, to: e.to, circular: e.circular, sx, sy, tx, ty, base, vx: base };
        });
        // Group by shared corridor (same rounded base x) and fan them out.
        const groups = new Map<number, typeof geoms>();
        geoms.forEach((g) => { const k = Math.round(g.base); const a = groups.get(k) || []; a.push(g); groups.set(k, a); });
        groups.forEach((list) => {
            const n = list.length;
            if (n < 2) return;
            list.sort((a, b) => a.sy - b.sy || a.ty - b.ty);
            const corridor = Math.abs(list[0].tx - list[0].sx);
            const spacing = Math.min(13, Math.max(0, (corridor - 16)) / (n - 1));
            list.forEach((g, i) => { g.vx = g.base + (i - (n - 1) / 2) * spacing; });
        });
        return geoms.map(({ base, ...rest }) => rest);
    }, [allEdges, itemPositions]);

    return (
        <div style={{ border: '1px solid var(--ss-border)', borderRadius: 'var(--ss-radius-sm)', maxWidth: '100%', overflow: 'hidden' }}>
            {/* Legend */}
            <div style={{ padding: '8px 12px', borderBottom: '1px solid var(--ss-border)', background: 'var(--ss-surface-2)', display: 'flex', gap: 18, alignItems: 'center', flexWrap: 'wrap', fontSize: 11.5, color: 'var(--ss-text-2)' }}>
                {(['create', 'update', 'delete'] as Op[]).map((op) => (
                    <span key={op} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                        <span style={{ width: 12, height: 12, background: opColors[op].fill, border: `1.5px solid ${opColors[op].stroke}`, borderRadius: 3 }} />
                        {op[0].toUpperCase() + op.slice(1)}
                    </span>
                ))}
                <span style={{ width: 1, height: 14, background: 'var(--ss-border)' }} />
                <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}><span style={{ color: 'var(--ss-green)' }}>✔</span> Success</span>
                <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}><span style={{ color: 'var(--ss-info)' }}>●</span> Running</span>
                <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}><span style={{ color: 'var(--ss-red)' }}>✖</span> Error</span>
                <span style={{ width: 1, height: 14, background: 'var(--ss-border)' }} />
                <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}><span style={{ color: 'var(--ss-amber)' }}>┄►</span> circular (2nd pass)</span>
            </div>

            <div style={{ overflow: 'auto', maxHeight: '85vh' }}>
            <svg width={Math.max(svgW, 320)} height={Math.max(svgH, 160)} style={{ display: 'block', background: 'var(--ss-bg)' }}>
                <defs>
                    <marker id="ssArrow" markerWidth="9" markerHeight="9" refX="7" refY="4" orient="auto">
                        <path d="M0,0 L8,4 L0,8 z" fill="var(--ss-text-2)" />
                    </marker>
                    <marker id="ssArrowHi" markerWidth="9" markerHeight="9" refX="7" refY="4" orient="auto">
                        <path d="M0,0 L8,4 L0,8 z" fill="var(--ss-accent)" />
                    </marker>
                    <marker id="ssArrowCirc" markerWidth="9" markerHeight="9" refX="7" refY="4" orient="auto">
                        <path d="M0,0 L8,4 L0,8 z" fill="var(--ss-amber)" />
                    </marker>
                </defs>

                {/* Step columns */}
                {columnRects.map((c, ci) => (
                    <g key={`col-${ci}`}>
                        <rect x={c.x} y={c.y} width={c.w} height={c.h} rx={8} ry={8} fill="var(--ss-surface-2)" stroke="var(--ss-border)" />
                        <text x={c.x + 12} y={c.y + 18} fontSize={11} fill="var(--ss-text-2)" fontWeight={600} style={{ textTransform: 'uppercase', letterSpacing: '.05em' }}>{c.label}</text>
                    </g>
                ))}

                {/* Table containers + items */}
                {batches.map((b) => (
                    <g key={`batch-${b.index}`}>
                        {b.tables.map((t) => {
                            const tbl = tablePositions[`${b.index}::${t.table}`];
                            if (!tbl) return null;
                            return (
                                <g key={`table-${b.index}-${t.table}`}>
                                    <rect x={tbl.x} y={tbl.y} width={tbl.w} height={tbl.h} rx={8} ry={8} fill="var(--ss-surface)" stroke="var(--ss-border)" />
                                    <rect x={tbl.x} y={tbl.y} width={tbl.w} height={tableHeaderH} rx={8} ry={8} fill="var(--ss-surface-3)" stroke="var(--ss-border)" />
                                    <rect x={tbl.x} y={tbl.y + tableHeaderH - 8} width={tbl.w} height={8} fill="var(--ss-surface-3)" />
                                    <text x={tbl.x + 12} y={tbl.y + 17} fontSize={11.5} fill="var(--ss-text)" fontWeight={600}>{shortType(t.table)}</text>

                                    {t.entries.map((entry) => {
                                        const key = `${t.table}:${entry.docId}`;
                                        const pos = itemPositions[key]!;
                                        const colors = opColors[entry.op];
                                        const si = statusIcon(syncItemsStatus[key]?.status);
                                        const full = label(t.table, entry.docId);
                                        const isHi = hoveredId === key;
                                        return (
                                            <g key={`item-${b.index}-${key}`} style={{ cursor: 'pointer' }}
                                               onClick={() => onInspect(t.table, entry.docId)}
                                               onMouseEnter={() => setHoveredId(key)} onMouseLeave={() => setHoveredId(null)}>
                                                <title>{full}</title>
                                                <rect x={pos.x} y={pos.y} width={pos.w} height={pos.h} rx={6} ry={6}
                                                      fill={colors.fill} stroke={isHi ? 'var(--ss-accent)' : colors.stroke} strokeWidth={isHi ? 2 : 1} />
                                                <text x={pos.x + 10} y={pos.y + pos.h / 2 + 4} fontSize={11} fill={si.color}>{si.char}</text>
                                                <text x={pos.x + 24} y={pos.y + pos.h / 2 + 4} fontSize={11.5} fill={colors.text} style={{ userSelect: 'none' }}>{trunc(full)}</text>
                                            </g>
                                        );
                                    })}
                                </g>
                            );
                        })}
                    </g>
                ))}

                {/* Edges above nodes */}
                <g style={{ pointerEvents: 'none' }}>
                    {edgeGeoms.map((g, idx) => {
                        const d = pathFor(g.sx, g.sy, g.tx, g.ty, g.vx);
                        const isHi = !!hoveredId && (g.from === hoveredId || g.to === hoveredId);
                        const color = isHi ? 'var(--ss-accent)' : g.circular ? 'var(--ss-amber)' : 'var(--ss-text-3)';
                        const marker = isHi ? 'url(#ssArrowHi)' : g.circular ? 'url(#ssArrowCirc)' : 'url(#ssArrow)';
                        const dash = g.circular ? '6,5' : '0';
                        const opacity = hoveredId ? (isHi ? 1 : 0.1) : 0.5;
                        const w = isHi ? 2.5 : 1.4;
                        return (
                            <g key={`edge-${idx}`} opacity={opacity}>
                                <path d={d} stroke="var(--ss-bg)" strokeWidth={w + 2.5} strokeDasharray={dash} fill="none" />
                                <path d={d} stroke={color} strokeWidth={w} strokeDasharray={dash} fill="none" markerEnd={marker} />
                            </g>
                        );
                    })}
                </g>
            </svg>
            </div>
        </div>
    );
};

export default SyncPlanGraph;
