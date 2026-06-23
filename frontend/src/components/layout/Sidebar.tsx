import React from 'react';
import { NavLink } from 'react-router-dom';
import { MenuItem } from 'primereact/menuitem';

interface SidebarProps {
  menuItems: MenuItem[];
}

const Sidebar: React.FC<SidebarProps> = ({ menuItems }) => (
  <aside className="ss-sidebar surface-card">
    <div className="ss-brand">
      <i className="pi pi-sync text-primary" aria-hidden="true" />
      <span>StrapiSync</span>
    </div>
    <nav>
      {menuItems.map((item) => (
        <NavLink
          key={item.url}
          to={item.url || '/'}
          className={({ isActive }) => `ss-nav-item${isActive ? ' active' : ''}`}
        >
          {item.icon && <i className={item.icon as string} aria-hidden="true" />}
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  </aside>
);

export default Sidebar;
