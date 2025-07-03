import { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import { Toast } from 'primereact/toast';
import { StrapiInstance, FormData } from '../types';

const initialFormData: FormData = {
  name: '',
  url: '',
  username: '',
  password: '',
  apiKey: ''
};

export const useInstanceManagement = () => {
  const [instances, setInstances] = useState<StrapiInstance[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState<boolean>(false);
  const [formData, setFormData] = useState<FormData>(initialFormData);
  const [isEditing, setIsEditing] = useState<boolean>(false);
  const [testingConnection, setTestingConnection] = useState<boolean>(false);
  const [connectionStatus, setConnectionStatus] = useState<{success: boolean, message: string} | null>(null);
  const toast = useRef<Toast>(null);

  // For instance details dialog
  const [showDetailsModal, setShowDetailsModal] = useState<boolean>(false);
  const [selectedInstanceId, setSelectedInstanceId] = useState<number | null>(null);
  const [fullInstanceData, setFullInstanceData] = useState<StrapiInstance | null>(null);
  const [loadingDetails, setLoadingDetails] = useState<boolean>(false);
  const [detailsError, setDetailsError] = useState<string | null>(null);

  const fetchInstances = async () => {
    try {
      setLoading(true);
      const response = await axios.get('/api/instances');
      setInstances(response.data);
      setError(null);
    } catch (err) {
      setError('Failed to fetch instances');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchInstances();
  }, []);

  const handleCloseModal = () => {
    setShowModal(false);
    setFormData(initialFormData);
    setIsEditing(false);
    setConnectionStatus(null);
  };

  const handleShowModal = (instance?: StrapiInstance) => {
    if (instance) {
      setFormData({
        id: instance.id,
        name: instance.name,
        url: instance.url,
        username: instance.username,
        password: '',
        apiKey: ''
      });
      setIsEditing(true);
    } else {
      setFormData(initialFormData);
      setIsEditing(false);
    }
    setShowModal(true);
    setConnectionStatus(null);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
    // Clear connection status when inputs change
    setConnectionStatus(null);
  };

  const handleTestConnection = async () => {
    try {
      setTestingConnection(true);
      const response = await axios.post('/api/instances/test-connection', {
        url: formData.url,
        apiKey: formData.apiKey,
        username: formData.username,
        password: formData.password
      });

      setConnectionStatus({
        success: response.data.connected,
        message: response.data.message
      });
    } catch (err) {
      setConnectionStatus({
        success: false,
        message: 'Error testing connection'
      });
    } finally {
      setTestingConnection(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    try {
      if (isEditing && formData.id) {
        await axios.put(`/api/instances/${formData.id}`, formData);
      } else {
        await axios.post('/api/instances', formData);
      }

      handleCloseModal();
      fetchInstances();
      toast.current?.show({
        severity: 'success',
        summary: 'Success',
        detail: isEditing ? 'Instance updated successfully' : 'Instance created successfully',
        life: 3000
      });
    } catch (err) {
      console.error('Error saving instance:', err);
      setError('Failed to save instance');
      toast.current?.show({
        severity: 'error',
        summary: 'Error',
        detail: 'Failed to save instance',
        life: 3000
      });
    }
  };

  const handleDelete = async (id: number) => {
    if (window.confirm('Are you sure you want to delete this instance?')) {
      try {
        await axios.delete(`/api/instances/${id}`);
        fetchInstances();
        toast.current?.show({
          severity: 'success',
          summary: 'Success',
          detail: 'Instance deleted successfully',
          life: 3000
        });
      } catch (err) {
        console.error('Error deleting instance:', err);
        setError('Failed to delete instance');
        toast.current?.show({
          severity: 'error',
          summary: 'Error',
          detail: 'Failed to delete instance',
          life: 3000
        });
      }
    }
  };

  // Handle showing the details modal
  const handleShowDetailsModal = (instanceId: number) => {
    setSelectedInstanceId(instanceId);
    setShowDetailsModal(true);
    setFullInstanceData(null);
    setDetailsError(null);
  };

  // Handle closing the details modal
  const handleCloseDetailsModal = () => {
    setShowDetailsModal(false);
    setSelectedInstanceId(null);
    setFullInstanceData(null);
    setDetailsError(null);
  };

  // Handle verifying the admin password and fetching full instance details
  const handleVerifyPassword = async (password: string, instanceId: number) => {
    try {
      setLoadingDetails(true);
      setDetailsError(null);

      const response = await axios.post(`/api/instances/${instanceId}/full`, { password });
      setFullInstanceData(response.data);
    } catch (err: any) {
      console.error('Error fetching instance details:', err);
      if (err.response && err.response.status === 401) {
        setDetailsError('Invalid admin password');
      } else {
        setDetailsError('Failed to fetch instance details');
      }
    } finally {
      setLoadingDetails(false);
    }
  };

  return {
    instances,
    loading,
    error,
    showModal,
    formData,
    isEditing,
    testingConnection,
    connectionStatus,
    toast,
    fetchInstances,
    handleCloseModal,
    handleShowModal,
    handleInputChange,
    handleTestConnection,
    handleSubmit,
    handleDelete,
    // Instance details dialog
    showDetailsModal,
    selectedInstanceId,
    fullInstanceData,
    loadingDetails,
    detailsError,
    handleShowDetailsModal,
    handleCloseDetailsModal,
    handleVerifyPassword
  };
};
