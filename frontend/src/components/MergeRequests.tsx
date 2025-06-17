import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Card } from 'primereact/card';
import { Button } from 'primereact/button';
import { DataTable, SortOrder } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Message } from 'primereact/message';
import { Toast } from 'primereact/toast';
import { Dialog } from 'primereact/dialog';
import { InputText } from 'primereact/inputtext';
import { Dropdown } from 'primereact/dropdown';
import { InputTextarea } from 'primereact/inputtextarea';
import { Tag } from 'primereact/tag';
import { ProgressSpinner } from 'primereact/progressspinner';
import { confirmDialog, ConfirmDialog } from 'primereact/confirmdialog';
import { TabView, TabPanel } from 'primereact/tabview';

interface StrapiInstance {
  id: number;
  name: string;
  url: string;
}

interface MergeRequest {
  id: number;
  name: string;
  description: string;
  sourceInstance: StrapiInstance;
  targetInstance: StrapiInstance;
  status: string;
  schemaCompatible: boolean | null;
  createdAt: string;
  updatedAt: string;
}

interface MergeRequestFormData {
  name: string;
  description: string;
  sourceInstanceId: number | null;
  targetInstanceId: number | null;
}

const MergeRequests: React.FC = () => {
  const [mergeRequests, setMergeRequests] = useState<MergeRequest[]>([]);
  const [instances, setInstances] = useState<StrapiInstance[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateDialog, setShowCreateDialog] = useState<boolean>(false);
  const [formData, setFormData] = useState<MergeRequestFormData>({
    name: '',
    description: '',
    sourceInstanceId: null,
    targetInstanceId: null
  });
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [activeTabIndex, setActiveTabIndex] = useState<number>(0);
  const [sortField, setSortField] = useState<string>("updatedAt");
  const [sortOrder, setSortOrder] = useState<SortOrder>(-1 as SortOrder); // -1 for DESC, 1 for ASC
  const [page, setPage] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const toast = useRef<Toast>(null);
  const navigate = useNavigate();

  // Fetch merge requests and instances
  const fetchData = async () => {
    try {
      setLoading(true);

      // Fetch instances if not already loaded
      if (instances.length === 0) {
        const instancesResponse = await axios.get('/api/instances');
        setInstances(instancesResponse.data);
      }

      // Determine completed status based on active tab
      const completed = activeTabIndex === 1 ? true : (activeTabIndex === 0 ? false : null);

      // Convert sort order from number to string
      const sortOrderStr = sortOrder === 1 ? 'ASC' : 'DESC';

      // Fetch merge requests with filtering, sorting, and pagination
      const mergeRequestsResponse = await axios.get('/api/merge-requests', {
        params: {
          completed,
          sortBy: sortField,
          sortOrder: sortOrderStr,
          page,
          pageSize
        }
      });

      setMergeRequests(mergeRequestsResponse.data);
      setError(null);
    } catch (err: any) {
      console.error('Error fetching data:', err);
      setError(err.response?.data?.message || 'Failed to fetch data');
    } finally {
      setLoading(false);
    }
  };

  // Fetch data on component mount and when parameters change
  useEffect(() => {
    fetchData();
  }, [activeTabIndex, sortField, sortOrder, page, pageSize]);

  // Handle form input changes
  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement> | { value: any, target: { name: string } }) => {
    // Handle both React.ChangeEvent and custom object with value property
    const name = e.target.name;
    const value = 'value' in e ? e.value : (e.target as HTMLInputElement | HTMLTextAreaElement).value;

    setFormData(prev => ({ ...prev, [name]: value }));

    // Clear error for this field
    if (formErrors[name]) {
      setFormErrors(prev => {
        const newErrors = { ...prev };
        delete newErrors[name];
        return newErrors;
      });
    }
  };

  // Validate form
  const validateForm = (): boolean => {
    const errors: Record<string, string> = {};

    if (!formData.name.trim()) {
      errors.name = 'Name is required';
    }

    if (!formData.description.trim()) {
      errors.description = 'Description is required';
    }

    if (formData.sourceInstanceId === null) {
      errors.sourceInstanceId = 'Source instance is required';
    }

    if (formData.targetInstanceId === null) {
      errors.targetInstanceId = 'Target instance is required';
    }

    if (formData.sourceInstanceId === formData.targetInstanceId && formData.sourceInstanceId !== null) {
      errors.targetInstanceId = 'Source and target instances must be different';
    }

    setFormErrors(errors);
    return Object.keys(errors).length === 0;
  };

  // Handle form submission
  const handleSubmit = async () => {
    if (!validateForm()) return;

    try {
      setSubmitting(true);

      const response = await axios.post('/api/merge-requests', formData);

      // Show success message
      toast.current?.show({
        severity: 'success',
        summary: 'Success',
        detail: 'Merge request created successfully',
        life: 3000
      });

      // Close the dialog and reset form
      setShowCreateDialog(false);
      setFormData({
        name: '',
        description: '',
        sourceInstanceId: null,
        targetInstanceId: null
      });

      // Make sure we're on the "Pending" tab since new merge requests are always pending
      if (activeTabIndex !== 0) {
        setActiveTabIndex(0);
      } else {
        // If already on the pending tab, just refresh the data
        fetchData();
      }

      // Navigate directly to the merge request details page
      navigate(`/merge-requests/${response.data.id}`);
    } catch (err: any) {
      console.error('Error creating merge request:', err);
      toast.current?.show({
        severity: 'error',
        summary: 'Error',
        detail: err.response?.data?.message || 'Failed to create merge request',
        life: 3000
      });
    } finally {
      setSubmitting(false);
    }
  };

  // Handle delete merge request
  const handleDelete = (id: number) => {
    confirmDialog({
      message: 'Are you sure you want to delete this merge request?',
      header: 'Confirm Delete',
      icon: 'pi pi-exclamation-triangle',
      acceptClassName: 'p-button-danger',
      accept: async () => {
        try {
          await axios.delete(`/api/merge-requests/${id}`);

          // Refresh the data to reflect the deletion
          fetchData();

          // Show success message
          toast.current?.show({
            severity: 'success',
            summary: 'Success',
            detail: 'Merge request deleted successfully',
            life: 3000
          });
        } catch (err: any) {
          console.error('Error deleting merge request:', err);
          toast.current?.show({
            severity: 'error',
            summary: 'Error',
            detail: err.response?.data?.message || 'Failed to delete merge request',
            life: 3000
          });
        }
      }
    });
  };

  // Navigate to merge request details
  const handleViewMergeRequest = (id: number) => {
    navigate(`/merge-requests/${id}`);
  };

  // Render status tag
  const statusTemplate = (rowData: MergeRequest) => {
    let severity: 'success' | 'info' | 'warning' | 'danger' = 'info';
    let label = rowData.status;

    switch (rowData.status) {
      case 'CREATED':
        severity = 'info';
        label = 'Created';
        break;
      case 'SCHEMA_CHECKED':
        severity = rowData.schemaCompatible ? 'success' : 'danger';
        label = rowData.schemaCompatible ? 'Schema Compatible' : 'Schema Incompatible';
        break;
      case 'COMPARED':
        severity = 'info';
        label = 'Content Compared';
        break;
      case 'MERGED_FILES':
        severity = 'info';
        label = 'Files Merged';
        break;
      case 'MERGED_SINGLES':
        severity = 'info';
        label = 'Singles Merged';
        break;
      case 'MERGED_COLLECTIONS':
        severity = 'info';
        label = 'Collections Merged';
        break;
      case 'COMPLETED':
        severity = 'success';
        label = 'Completed';
        break;
      case 'FAILED':
        severity = 'danger';
        label = 'Failed';
        break;
    }

    return <Tag severity={severity} value={label} />;
  };

  // Render instances
  const instancesTemplate = (rowData: MergeRequest) => {
    // Check if sourceInstance and targetInstance exist before accessing their properties
    const sourceName = rowData.sourceInstance?.name || 'Unknown';
    const targetName = rowData.targetInstance?.name || 'Unknown';

    return (
      <div className="flex flex-column">
        <div className="mb-2">
          <span className="font-bold mr-2">Source:</span>
          <Tag value={sourceName} severity="info" />
        </div>
        <div>
          <span className="font-bold mr-2">Target:</span>
          <Tag value={targetName} severity="warning" />
        </div>
      </div>
    );
  };

  // Render actions
  const actionsTemplate = (rowData: MergeRequest) => {
    return (
      <div className="flex gap-2">
        <Button 
          icon="pi pi-eye" 
          className="p-button-rounded p-button-outlined p-button-primary p-button-sm" 
          onClick={() => handleViewMergeRequest(rowData.id)}
          tooltip="View Details"
        />
        {/* Only show delete button for non-completed merge requests (first tab) */}
        {activeTabIndex === 0 && (
          <Button 
            icon="pi pi-trash" 
            className="p-button-rounded p-button-outlined p-button-danger p-button-sm"
            onClick={() => handleDelete(rowData.id)}
            tooltip="Delete"
          />
        )}
      </div>
    );
  };

  // Handle sort change
  const onSort = (event: any) => {
    setSortField(event.sortField);
    setSortOrder(event.sortOrder);
    setPage(1); // Reset to first page when sorting changes
  };

  // Handle page change
  const onPage = (event: any) => {
    setPage(event.page + 1); // PrimeReact uses 0-based indexing, our API uses 1-based
    setPageSize(event.rows);
  };

  // Loading state
  if (loading && mergeRequests.length === 0) {
    return <div className="flex justify-content-center p-5"><ProgressSpinner /></div>;
  }

  return (
    <div className="container py-4">
      <Toast ref={toast} />
      <ConfirmDialog />

      <Card className="mb-4">
        <div className="flex justify-content-between align-items-center p-3 bg-primary text-white">
          <h2 className="m-0">Merge Requests</h2>
          <Button 
            label="Create Merge Request" 
            icon="pi pi-plus" 
            onClick={() => setShowCreateDialog(true)} 
          />
        </div>
        <div className="p-3">
          {error && <Message severity="error" text={error} className="w-full mb-3" />}

          <TabView 
            activeIndex={activeTabIndex} 
            onTabChange={(e) => {
              setActiveTabIndex(e.index);
              setPage(1); // Reset to first page when changing tabs
            }}
          >
            <TabPanel header="Pending Merge Requests">
              {mergeRequests.length === 0 && !loading ? (
                <Message 
                  severity="info" 
                  text="No pending merge requests found. Create your first merge request to get started." 
                  className="w-full" 
                />
              ) : (
                <DataTable 
                  value={mergeRequests} 
                  responsiveLayout="stack" 
                  breakpoint="960px"
                  stripedRows 
                  paginator 
                  rows={pageSize} 
                  rowsPerPageOptions={[5, 10, 25, 50]}
                  emptyMessage="No pending merge requests found"
                  className="p-datatable-sm"
                  lazy
                  first={(page - 1) * pageSize}
                  totalRecords={mergeRequests.length}
                  onPage={onPage}
                  loading={loading}
                  sortField={sortField}
                  sortOrder={sortOrder}
                  onSort={onSort}
                >
                  <Column field="name" header="Name" sortable />
                  <Column field="description" header="Description" />
                  <Column header="Instances" body={instancesTemplate} />
                  <Column header="Status" body={statusTemplate} sortable sortField="status" />
                  <Column 
                    field="updatedAt" 
                    header="Last Updated" 
                    sortable 
                    body={(rowData) => new Date(rowData.updatedAt).toLocaleString()} 
                  />
                  <Column 
                    field="createdAt" 
                    header="Created" 
                    sortable 
                    body={(rowData) => new Date(rowData.createdAt).toLocaleString()} 
                  />
                  <Column header="Actions" body={actionsTemplate} style={{ width: '10rem' }} />
                </DataTable>
              )}
            </TabPanel>
            <TabPanel header="Completed Merge Requests">
              {mergeRequests.length === 0 && !loading ? (
                <Message 
                  severity="info" 
                  text="No completed merge requests found." 
                  className="w-full" 
                />
              ) : (
                <DataTable 
                  value={mergeRequests} 
                  responsiveLayout="stack" 
                  breakpoint="960px"
                  stripedRows 
                  paginator 
                  rows={pageSize} 
                  rowsPerPageOptions={[5, 10, 25, 50]}
                  emptyMessage="No completed merge requests found"
                  className="p-datatable-sm"
                  lazy
                  first={(page - 1) * pageSize}
                  totalRecords={mergeRequests.length}
                  onPage={onPage}
                  loading={loading}
                  sortField={sortField}
                  sortOrder={sortOrder}
                  onSort={onSort}
                >
                  <Column field="name" header="Name" sortable />
                  <Column field="description" header="Description" />
                  <Column header="Instances" body={instancesTemplate} />
                  <Column header="Status" body={statusTemplate} sortable sortField="status" />
                  <Column 
                    field="updatedAt" 
                    header="Last Updated" 
                    sortable 
                    body={(rowData) => new Date(rowData.updatedAt).toLocaleString()} 
                  />
                  <Column 
                    field="createdAt" 
                    header="Created" 
                    sortable 
                    body={(rowData) => new Date(rowData.createdAt).toLocaleString()} 
                  />
                  <Column header="Actions" body={actionsTemplate} style={{ width: '10rem' }} />
                </DataTable>
              )}
            </TabPanel>
          </TabView>
        </div>
      </Card>

      {/* Create Merge Request Dialog */}
      <Dialog 
        header="Create Merge Request" 
        visible={showCreateDialog} 
        style={{ width: '50vw' }} 
        onHide={() => setShowCreateDialog(false)}
        footer={
          <div>
            <Button 
              label="Cancel" 
              icon="pi pi-times" 
              className="p-button-text" 
              onClick={() => setShowCreateDialog(false)} 
              disabled={submitting}
            />
            <Button 
              label="Create" 
              icon="pi pi-check" 
              onClick={handleSubmit} 
              loading={submitting}
            />
          </div>
        }
      >
        <div className="p-fluid">
          <div className="field">
            <label htmlFor="name">Name</label>
            <InputText 
              id="name" 
              name="name" 
              value={formData.name} 
              onChange={handleInputChange} 
              className={formErrors.name ? 'p-invalid' : ''}
            />
            {formErrors.name && <small className="p-error">{formErrors.name}</small>}
          </div>

          <div className="field">
            <label htmlFor="description">Description</label>
            <InputTextarea 
              id="description" 
              name="description" 
              value={formData.description} 
              onChange={handleInputChange} 
              rows={3} 
              className={formErrors.description ? 'p-invalid' : ''}
            />
            {formErrors.description && <small className="p-error">{formErrors.description}</small>}
          </div>

          <div className="field">
            <label htmlFor="sourceInstanceId">Source Instance</label>
            <Dropdown 
              id="sourceInstanceId" 
              name="sourceInstanceId" 
              value={formData.sourceInstanceId} 
              options={instances} 
              onChange={handleInputChange} 
              optionLabel="name" 
              optionValue="id" 
              placeholder="Select a source instance" 
              className={formErrors.sourceInstanceId ? 'p-invalid' : ''}
            />
            {formErrors.sourceInstanceId && <small className="p-error">{formErrors.sourceInstanceId}</small>}
          </div>

          <div className="field">
            <label htmlFor="targetInstanceId">Target Instance</label>
            <Dropdown 
              id="targetInstanceId" 
              name="targetInstanceId" 
              value={formData.targetInstanceId} 
              options={instances} 
              onChange={handleInputChange} 
              optionLabel="name" 
              optionValue="id" 
              placeholder="Select a target instance" 
              className={formErrors.targetInstanceId ? 'p-invalid' : ''}
            />
            {formErrors.targetInstanceId && <small className="p-error">{formErrors.targetInstanceId}</small>}
          </div>
        </div>
      </Dialog>
    </div>
  );
};

export default MergeRequests;
