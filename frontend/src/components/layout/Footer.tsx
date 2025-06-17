import React, { useContext } from 'react';
import { LayoutContext } from '../../layout/context/layoutcontext';

const Footer: React.FC = () => {
  const { layoutConfig } = useContext(LayoutContext);

  return (
    <div className={`${layoutConfig.colorScheme === 'dark' ? 'surface-card' : 'surface-900'} ${layoutConfig.colorScheme === 'dark' ? 'text-primary' : 'text-white'} p-3 mt-auto`}>
      <div className="container">
        <p className="m-0 text-center">Strapi Sync &copy; {new Date().getFullYear()}</p>
      </div>
    </div>
  );
};

export default Footer;
