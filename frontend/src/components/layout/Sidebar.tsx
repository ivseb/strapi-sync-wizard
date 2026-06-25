import React from 'react';
import { NavLink } from 'react-router-dom';
import { useMergeRequests } from '../../api/mergeRequests';

const NAV = [
  { to: '/merge-requests', icon: 'pi pi-git-pull-request', label: 'Merge requests', counted: true },
  { to: '/instances', icon: 'pi pi-server', label: 'Instances', counted: false },
  { to: '/media-cleanup', icon: 'pi pi-images', label: 'Media cleanup', counted: false },
  { to: '/content-cleanup', icon: 'pi pi-clone', label: 'Content cleanup', counted: false },
  { to: '/snapshots', icon: 'pi pi-database', label: 'Snapshots', counted: false },
];

const Sidebar: React.FC = () => {
  const { data } = useMergeRequests();
  const mrCount = Array.isArray(data) ? data.length : undefined;

  return (
    <aside className="ss-sidebar">
      <div className="ss-brand">
        <span className="ss-brand-mark"><i className="pi pi-sync" aria-hidden="true" /></span>
        <span>Strapi Sync</span>
      </div>
      <nav>
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) => `ss-nav-item${isActive ? ' active' : ''}`}
          >
            <i className={item.icon} aria-hidden="true" />
            <span>{item.label}</span>
            {item.counted && mrCount !== undefined && <span className="ss-nav-count">{mrCount}</span>}
          </NavLink>
        ))}
      </nav>
      <div className="ss-sidebar-foot">
        <span className="ss-avatar">IS</span>
        <span>signed in</span>
      </div>
    </aside>
  );
};

export default Sidebar;
