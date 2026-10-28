import React from 'react';
import { TypeName } from '../../utils/typeNames';

/** A content type's name: admin name, then the API id in parentheses (table name in the tooltip). */
export const TypeLabel: React.FC<{ name: TypeName; compact?: boolean }> = ({ name, compact }) => (
  <span className="ss-type" title={`${name.admin} — API id: ${name.api}${name.table ? ` · table: ${name.table}` : ''}`}>
    {name.admin}
    {!compact && name.api !== 'files' && <span className="ss-type-api"> ({name.api})</span>}
  </span>
);

/** DOM id of a group (its wrapper, not the sticky header: a stuck element can't be scrolled to). */
export const groupDomId = (scope: string, key: string): string => `ss-grp-${scope}-${key.replace(/[^a-zA-Z0-9_-]/g, '_')}`;

export interface IndexItem {
  key: string;
  name: TypeName;
  add: number;
  upd: number;
  del: number;
}

/** Scroll a group header into view (after the caller expanded it). */
export const jumpToGroup = (scope: string, key: string): void => {
  requestAnimationFrame(() => {
    const el = document.getElementById(groupDomId(scope, key));
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    const head = el.querySelector('.ss-group-head');
    head?.classList.add('ss-flash');
    window.setTimeout(() => head?.classList.remove('ss-flash'), 900);
  });
};

/**
 * Quick access to the groups of a long list: a sticky side index on wide screens, a "jump to" select
 * in the toolbar on narrow ones (CSS decides which one shows).
 */
export const TypeIndex: React.FC<{ items: IndexItem[]; onJump: (key: string) => void }> = ({ items, onJump }) => (
  <aside className="ss-typeindex" aria-label="Content types">
    <div className="ss-typeindex-title">Types · {items.length}</div>
    {items.map((it) => (
      <button key={it.key} className="ss-typeindex-item" onClick={() => onJump(it.key)} title={`${it.name.admin} (${it.name.api})`}>
        <span className="ss-typeindex-name">{it.name.admin}</span>
        <span className="ss-typeindex-counts">
          {it.add > 0 && <span className="ss-count add">+{it.add}</span>}
          {it.upd > 0 && <span className="ss-count upd">~{it.upd}</span>}
          {it.del > 0 && <span className="ss-count del">−{it.del}</span>}
        </span>
      </button>
    ))}
  </aside>
);

export const TypeJump: React.FC<{ items: IndexItem[]; onJump: (key: string) => void }> = ({ items, onJump }) => (
  <select className="ss-typejump" value="" onChange={(e) => { if (e.target.value) onJump(e.target.value); }} aria-label="Jump to type">
    <option value="">Jump to type…</option>
    {items.map((it) => <option key={it.key} value={it.key}>{it.name.admin} ({it.name.api})</option>)}
  </select>
);

export const ExpandCollapseAll: React.FC<{ onExpand: () => void; onCollapse: () => void }> = ({ onExpand, onCollapse }) => (
  <span className="ss-expandall">
    <button className="ss-btn subtle" onClick={onExpand} title="Expand all groups"><i className="pi pi-angle-double-down" aria-hidden="true" /> Expand all</button>
    <button className="ss-btn subtle" onClick={onCollapse} title="Collapse all groups"><i className="pi pi-angle-double-up" aria-hidden="true" /> Collapse all</button>
  </span>
);
