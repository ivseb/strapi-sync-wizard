import React, {useMemo, useState} from 'react';
import {SyncPlanDTO, Direction} from '../../../types';

export interface SyncPlanGraphProps {
    syncPlan: SyncPlanDTO;
    // key: `${table}:${documentId}` -> status
    syncItemsStatus: Record<string, { status: string; message?: string }>;
    onInspect: (tableName: string, documentId: string) => void;
}

// Small helpers
const opToLabel = (dir: Direction): 'create' | 'update' | 'delete' =>
    dir === 'TO_CREATE' ? 'create' : dir === 'TO_UPDATE' ? 'update' : 'delete';

const opColors: Record<'create' | 'update' | 'delete', { fill: string; stroke: string; text: string } > = {
    create: { fill: '#dcfce7', stroke: '#16a34a', text: '#166534' },
    update: { fill: '#fef3c7', stroke: '#d97706', text: '#92400e' },
    delete: { fill: '#fee2e2', stroke: '#dc2626', text: '#991b1b' },
};

const statusIcon = (status?: string): { char: string; color: string; title: string } => {
    switch (status) {
        case 'IN_PROGRESS':
            return { char: '●', color: '#6366f1', title: 'In progress' };
        case 'SUCCESS':
            return { char: '✔', color: '#16a34a', title: 'Success' };
        case 'ERROR':
            return { char: '✖', color: '#dc2626', title: 'Error' };
        default:
            return { char: '●', color: '#94a3b8', title: 'Pending' };
    }
};

const SyncPlanGraph: React.FC<SyncPlanGraphProps> = ({ syncPlan, syncItemsStatus, onInspect }) => {
    const [hoveredId, setHoveredId] = useState<string | null>(null);
    // Build a flat list of items from batches and keep batch index
    const items = useMemo(() => {
        const list: { table: string; docId: string; op: 'create'|'update'|'delete'; batch: number }[] = [];
        (syncPlan.batches || []).forEach((batch, bi) => {
            batch.forEach(it => list.push({ table: it.tableName, docId: it.documentId, op: opToLabel(it.direction), batch: bi }));
        });
        // Remove duplicates if any (same table:docId may appear once)
        const seen = new Set<string>();
        return list.filter(i => {
            const k = `${i.table}:${i.docId}`;
            if (seen.has(k)) return false;
            seen.add(k);
            return true;
        });
    }, [syncPlan]);

    // Group items by batch, then by table (one container per table per step)
    const batches = useMemo(() => {
        const res: { index: number; tables: { table: string; entries: { docId: string; op: 'create'|'update'|'delete' }[] }[] }[] = [];
        const batchCount = (syncPlan.batches || []).length;
        for (let bi = 0; bi < batchCount; bi++) {
            const byTable = new Map<string, { table: string; entries: { docId: string; op: 'create'|'update'|'delete' }[] }>();
            items.filter(i => i.batch === bi).forEach(i => {
                if (!byTable.has(i.table)) byTable.set(i.table, { table: i.table, entries: [] });
                byTable.get(i.table)!.entries.push({ docId: i.docId, op: i.op });
            });
            const tables = Array.from(byTable.values()).sort((a,b) => a.table.localeCompare(b.table));
            res.push({ index: bi, tables });
        }
        return res;
    }, [items, syncPlan]);

    // Layout constants
    const padding = 24;
    const tableBoxW = 260; // width of each table container
    const tableHeaderH = 28;
    const batchHeaderH = 30;
    const itemH = 24;
    const itemGap = 6;
    const colGap = 56; // gap between batch columns
    const tableGapY = 18; // gap between table boxes vertically inside a column

    // Compute layout positions for each table (per batch) and each item; also compute column backgrounds
    type ItemPos = { x: number; y: number; w: number; h: number };
    const { svgW, svgH, tablePositions, itemPositions, columnRects } = useMemo(() => {
        const tablePositions: Record<string, { x: number; y: number; w: number; h: number } > = {};
        const itemPositions: Record<string, ItemPos> = {};
        const columnRects: { x: number; y: number; w: number; h: number; label: string }[] = [];

        const columns = batches.length;
        const svgW = padding * 2 + Math.max(0, columns) * tableBoxW + Math.max(0, columns - 1) * colGap;

        // Compute column heights and positions
        let maxColumnH = 0;
        for (let bi = 0; bi < columns; bi++) {
            const colX = padding + bi * (tableBoxW + colGap);
            let y = padding + batchHeaderH + 8; // space for header inside column
            const tables = batches[bi].tables;
            let colH = batchHeaderH + 8; // start with header height
            tables.forEach(t => {
                const itemsCount = t.entries.length;
                const listH = itemsCount * itemH + Math.max(0, itemsCount - 1) * itemGap;
                const h = tableHeaderH + 12 + listH + 12; // padding
                const key = `${bi}::${t.table}`;
                tablePositions[key] = { x: colX, y, w: tableBoxW, h };

                // items
                const listX = colX + 12;
                let listY = y + tableHeaderH + 12;
                t.entries.forEach(entry => {
                    const itemKey = `${t.table}:${entry.docId}`;
                    itemPositions[itemKey] = { x: listX, y: listY, w: tableBoxW - 24, h: itemH };
                    listY += itemH + itemGap;
                });

                y += h + tableGapY;
                colH += h + tableGapY;
            });
            // remove last extra gap
            colH = Math.max(batchHeaderH + 8, colH - tableGapY);
            maxColumnH = Math.max(maxColumnH, colH);
            columnRects.push({ x: colX - 8, y: padding, w: tableBoxW + 16, h: colH + 8, label: `Step ${bi + 1}` });
        }

        const svgH = padding * 2 + maxColumnH;
        return { svgW, svgH, tablePositions, itemPositions, columnRects };
    }, [batches]);

    // Build edges list with circular flag
    const allEdges = useMemo(() => {
        const normal = (syncPlan.edges || []).map(e => ({
            from: `${e.fromTable}:${e.fromDocumentId}`,
            to: `${e.toTable}:${e.toDocumentId}`,
            via: e.viaField,
            circular: false as const
        }));
        const circular = (syncPlan.circularEdges || []).map(e => ({
            from: `${e.fromTable}:${e.fromDocumentId}`,
            to: `${e.toTable}:${e.toDocumentId}`,
            via: e.viaField,
            circular: true as const
        }));
        // filter to those with both endpoints visible
        return [...normal, ...circular].filter(e => itemPositions[e.from] && itemPositions[e.to]);
    }, [syncPlan, itemPositions]);

    // Edge routing: connect from right edge of source item to left edge of target item
    const leftAnchor = (pos: ItemPos): { x: number; y: number } => ({
        x: pos.x + 4,
        y: pos.y + pos.h / 2
    });
    const rightAnchor = (pos: ItemPos): { x: number; y: number } => ({
        x: pos.x + pos.w - 4,
        y: pos.y + pos.h / 2
    });
    const pathFor = (from: ItemPos, to: ItemPos) => {
        const s = rightAnchor(from);
        const t = leftAnchor(to);
        const dx = Math.max(40, (t.x - s.x) / 2);
        return `M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${t.x - dx} ${t.y}, ${t.x} ${t.y}`;
    };

    return (
        <div style={{ overflowX: 'auto', overflowY: 'hidden', border: '1px solid var(--surface-border)', borderRadius: 6,  maxWidth: '100%', margin: '0 auto'  }}>
            {/* Legend */}
            <div style={{ padding: 12, borderBottom: '1px solid var(--surface-border)', background: 'rgba(255,255,255,0.97)' }}>
                <div style={{ marginBottom: 8 }}>
                    <div style={{ display: 'inline-block', background: '#e2e8f0', padding: '6px 10px', borderRadius: 6 }}>
                        <span style={{ color: '#0f172a', fontWeight: 700 }}>Legend</span>
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 24, alignItems: 'center', flexWrap: 'wrap', background: 'rgba(255,255,255,0.04)', padding: 8, borderRadius: 6 }}>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                        <span style={{ display: 'inline-block', width: 14, height: 14, background: opColors.create.fill, border: `2px solid ${opColors.create.stroke}`, borderRadius: 2 }} />
                        <span style={{ color: '#0f172a' }}>Create</span>
                    </div>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                        <span style={{ display: 'inline-block', width: 14, height: 14, background: opColors.update.fill, border: `2px solid ${opColors.update.stroke}`, borderRadius: 2 }} />
                        <span style={{ color: '#0f172a' }}>Update</span>
                    </div>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                        <span style={{ display: 'inline-block', width: 14, height: 14, background: opColors.delete.fill, border: `2px solid ${opColors.delete.stroke}`, borderRadius: 2 }} />
                        <span style={{ color: '#0f172a' }}>Delete</span>
                    </div>
                    <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginLeft: 12 }}>
                        <span style={{ color: '#16a34a' }}>✔</span><span style={{ color: '#0f172a' }}>Success</span>
                        <span style={{ color: '#6366f1', marginLeft: 12 }}>●</span><span style={{ color: '#0f172a' }}>In progress</span>
                        <span style={{ color: '#dc2626', marginLeft: 12 }}>✖</span><span style={{ color: '#0f172a' }}>Error</span>
                    </div>
                </div>
            </div>
            <svg width={svgW} height={Math.max(svgH, 300)} style={{ overflowX: 'auto', width: '100%', background: 'rgb(233,233,233)' }}>


                {/* Column backgrounds and headers (order left -> right) */}
                {columnRects.map((c, ci) => (
                    <g key={`col-${ci}`}>
                        <rect x={c.x} y={c.y} width={c.w} height={c.h} rx={8} ry={8} fill={"rgba(255,255,255,0.97)"} stroke="#1f2937" />
                        <text x={c.x + 10} y={c.y + 20} fontSize={12} fill="#0f172a" fontWeight={700}>{c.label}</text>
                    </g>
                ))}

                {/* Draw table boxes and items per batch column */}
                {batches.map((b) => (
                    <g key={`batch-${b.index}`}>
                        {b.tables.map(t => {
                            const tbl = tablePositions[`${b.index}::${t.table}`];
                            if (!tbl) return null;
                            return (
                                <g key={`table-${b.index}-${t.table}`}>
                                    {/* Container */}
                                    <rect x={tbl.x} y={tbl.y} width={tbl.w} height={tbl.h} rx={8} ry={8} fill="#ffffff" stroke="#cbd5e1" />
                                    {/* Header */}
                                    <rect x={tbl.x} y={tbl.y} width={tbl.w} height={tableHeaderH} rx={8} ry={8} fill="#f1f5f9" stroke="#cbd5e1" />
                                    <text x={tbl.x + 10} y={tbl.y + 18} fontSize={12} fill="#0f172a" fontWeight={600}>{t.table}</text>

                                    {/* Items */}
                                    {t.entries.map((entry) => {
                                        const key = `${t.table}:${entry.docId}`;
                                        const pos = itemPositions[key]!;
                                        const colors = opColors[entry.op];
                                        const live = syncItemsStatus[key]?.status;
                                        const si = statusIcon(live);
                                        return (
                                            <g key={`item-${b.index}-${key}`}>
                                                <rect x={pos.x} y={pos.y} width={pos.w} height={pos.h} rx={6} ry={6} fill={colors.fill} stroke={colors.stroke} />
                                                {/* id text */}
                                                <text x={pos.x + 22} y={pos.y + pos.h/2 + 4} fontSize={11} fill={colors.text} style={{ userSelect: 'none' }}>{entry.docId}</text>
                                                {/* status icon */}
                                                <text x={pos.x + 8} y={pos.y + pos.h/2 + 4} fontSize={11} fill={si.color}>{si.char}</text>
                                                {/* click/hover hotspot */}
                                                <rect x={pos.x} y={pos.y} width={pos.w} height={pos.h} fill="transparent" style={{ cursor: 'pointer' }} onClick={() => onInspect(t.table, entry.docId)} onMouseEnter={() => setHoveredId(key)} onMouseLeave={() => setHoveredId(null)} />
                                            </g>
                                        );
                                    })}
                                </g>
                            );
                        })}
                    </g>
                ))}

                {/* Draw edges ABOVE nodes with hover highlighting */}
                <g style={{ pointerEvents: 'none' }}>
                {allEdges.map((e, idx) => {
                    const p1 = itemPositions[e.from];
                    const p2 = itemPositions[e.to];
                    if (!p1 || !p2) return null;
                    // Offset target a bit to avoid arrowhead overlapping the box producing a dark cone
                    const d = pathFor(p1, { ...p2, x: p2.x - 6 });
                    const baseColor = e.circular ? '#7c3aed' : '#0f172a';
                    const isHighlighted = !!hoveredId && (e.from === hoveredId || e.to === hoveredId);
                    const drawColor = isHighlighted ? (e.circular ? '#a855f7' : '#0ea5e9') : baseColor;
                    const marker = e.circular ? 'url(#edgeArrowCircular)' : 'url(#edgeArrowStrong)';
                    const dash = e.circular ? '6,6' : '0';
                    const opacity = hoveredId ? (isHighlighted ? 1 : 0.25) : 1;
                    const strokeW = isHighlighted ? 4 : 3;
                    const haloW = strokeW + 2;
                    return (
                        <g key={`edge-top-${idx}`} filter="url(#edgeShadow)" opacity={opacity}>
                            {/* halo */}
                            <path d={d} stroke="#ffffff" strokeWidth={haloW} strokeDasharray={dash} fill="none" opacity={isHighlighted ? 0.95 : 0.85} />
                            {/* main colored path */}
                            <path d={d} stroke={drawColor} strokeWidth={strokeW} strokeDasharray={dash} fill="none" markerEnd={marker} />
                        </g>
                    );
                })}
                </g>
            </svg>
        </div>
    );
};

export default SyncPlanGraph;
