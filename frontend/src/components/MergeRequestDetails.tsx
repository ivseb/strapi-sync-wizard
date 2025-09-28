import React, {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import axios from 'axios';
import {Card} from 'primereact/card';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';
import {Toast} from 'primereact/toast';
import {ProgressSpinner} from 'primereact/progressspinner';
import {Tag} from 'primereact/tag';
import {Stepper} from 'primereact/stepper';
import {StepperPanel} from 'primereact/stepperpanel';

// Import types
import {
    ContentTypeComparisonResultKind,
    ContentTypeComparisonResultWithRelationships,
    MergeRequestData,
    MergeRequestDetail,
    StrapiContentTypeKind
} from '../types';

// Step Components
import SchemaCompatibilityStep from './steps/SchemaCompatibilityStep';
import ContentComparisonStep from './steps/ContentComparisonStep';
import MergeFilesStep from './steps/MergeFilesStep';
import MergeSingleTypesStep from './steps/MergeSingleTypesStep';
import MergeCollectionsStep from './steps/MergeCollectionsStep';
import CompleteMergeStep from './steps/CompleteMergeStep';

const MergeRequestDetails: React.FC = () => {
    console.log('Rendering MergeRequestDetails');
    const {id} = useParams<{ id: string }>();
    const navigate = useNavigate();
    const toast = useRef<Toast>(null);

    const [mergeRequestDetail, setMergeRequestDetail] = useState<MergeRequestDetail | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [activeStep, setActiveStep] = useState<number>(0);
    const [checkingSchema, setCheckingSchema] = useState<boolean>(false);
    const [comparingContent, setComparingContent] = useState<boolean>(false);
    const [mergingCollections, setMergingCollections] = useState<boolean>(false);
    const [completing, setCompleting] = useState<boolean>(false);

    // Create a ref for the stepper
    const stepperRef = useRef<any>(null);

    const updateMergeData = (data?: MergeRequestData) => {
        if (!data || !mergeRequestDetail) return
        const mergeRequestDetailNew = {
            ...mergeRequestDetail,
            mergeRequestData: data
        }
        setMergeRequestDetail(mergeRequestDetailNew)

    }


    // No need for a separate function to fetch merge data as it's now included in the main API response

    // Fetch merge request details
    useEffect(() => {
        const fetchMergeRequest = async () => {
            try {
                setLoading(true);

                const response = await axios.get(`/api/merge-requests/${id}`);
                setMergeRequestDetail(response.data);

                // Set active step based on status (after extracting schema and comparison steps)
                let stepIndex = 0;
                switch (response.data.mergeRequest.status) {
                    case 'MERGED_FILES':
                        stepIndex = 1;
                        break;
                    case 'MERGED_SINGLES':
                        stepIndex = 2;
                        break;
                    case 'MERGED_COLLECTIONS':
                    case 'IN_PROGRESS':
                    case 'FAILED':
                    case 'COMPLETED':
                        stepIndex = 2;
                        break;
                    default:
                        stepIndex = 0; // CREATED, SCHEMA_CHECKED, COMPARED
                }

                setActiveStep(stepIndex);
                // We'll set the active step in the stepper using the activeIndex prop

                setError(null);
            } catch (err: any) {
                console.error('Error fetching merge request:', err);
                setError(err.response?.data?.message || 'Failed to fetch merge request');
            } finally {
                setLoading(false);
            }
        };

        if (id) {
            fetchMergeRequest();
        }

        // No need for cleanup function as we're not using localStorage anymore
    }, [id]);

    // Check schema compatibility
    const checkSchemaCompatibility = async (force: boolean = false) => {
        if (!id) return;

        try {
            setCheckingSchema(true);

            const response = await axios.post(`/api/merge-requests/${id}/check-schema?force=${force}`);
            const {isCompatible} = response.data;

            // Fetch the updated merge request details
            const updatedMergeRequest = await axios.get(`/api/merge-requests/${id}`);
            setMergeRequestDetail(updatedMergeRequest.data);

            // Show success message
            toast.current?.show({
                severity: isCompatible ? 'success' : 'warn',
                summary: isCompatible ? 'Compatible Schemas' : 'Incompatible Schemas',
                detail: isCompatible
                    ? 'The schemas of the source and target instances are compatible.'
                    : 'The schemas of the source and target instances are not compatible.',
                life: 5000
            });

            // After check, keep the wizard on the first merge step
            if (isCompatible) {
                setActiveStep(0);
            }
        } catch (err: any) {
            console.error('Error checking schema compatibility:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to check schema compatibility',
                life: 5000
            });
        } finally {
            setCheckingSchema(false);
        }
    };

    // Compare content
    const compareContent = async () => {
        if (!id) return;

        try {
            setComparingContent(true);

            // Call the compare endpoint with new mode parameter (Full recompute)
            await axios.post(`/api/merge-requests/${id}/compare?mode=full`);

            // Fetch the updated merge request details
            const updatedMergeRequest = await axios.get(`/api/merge-requests/${id}`);
            setMergeRequestDetail(updatedMergeRequest.data);

            // Show success message
            toast.current?.show({
                severity: 'success',
                summary: 'Content Compared',
                detail: 'Content comparison completed successfully.',
                life: 5000
            });

            // After comparison, start from the first merge step
            setActiveStep(0);
        } catch (err: any) {
            console.error('Error comparing content:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to compare content',
                life: 5000
            });
        } finally {
            setComparingContent(false);
        }
    };


    // Proceed to next step
    const proceedToNextStep = async (nextStep: number) => {
        if (!id) return;

        // Only update status when moving forward
        if (nextStep > activeStep && mergeRequestDetail) {
            // Determine the new status based on the next step
            let newStatus = mergeRequestDetail.mergeRequest.status;

            if (nextStep === 1 && mergeRequestDetail.mergeRequest.status === 'COMPARED') {
                newStatus = 'MERGED_FILES';
                await updateMergeRequestStatus(newStatus);
            } else if (nextStep === 2 && mergeRequestDetail.mergeRequest.status === 'MERGED_FILES') {
                newStatus = 'MERGED_SINGLES';
                await updateMergeRequestStatus(newStatus);
            } else if (nextStep === 3 && mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES') {
                newStatus = 'MERGED_COLLECTIONS';
                await updateMergeRequestStatus(newStatus);
            }
        }

        setActiveStep(nextStep);
    };

    // Update merge request status
    const updateMergeRequestStatus = async (status: string) => {
        if (!id || !mergeRequestDetail) return false;

        try {
            // Update the merge request with the new status
            await axios.put(`/api/merge-requests/${id}`, {
                id: mergeRequestDetail.mergeRequest.id,
                name: mergeRequestDetail.mergeRequest.name,
                description: mergeRequestDetail.mergeRequest.description,
                sourceInstanceId: mergeRequestDetail.mergeRequest.sourceInstance.id,
                targetInstanceId: mergeRequestDetail.mergeRequest.targetInstance.id,
                status: status
            });

            const updatedMergeRequest = await axios.get(`/api/merge-requests/${id}`);
            setMergeRequestDetail(updatedMergeRequest.data);

            return true;
        } catch (err: any) {
            console.error('Error updating merge request status:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to update merge request status',
                life: 5000
            });
            return false;
        }
    };


    // Function to update selections for a list or all (unified API)
    const updateAllSelections = async (kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string, documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind) => {
        if (!id) return false;

        try {
            await axios.post(`/api/merge-requests/${id}/selection`, {
                kind,
                tableName,
                ids: documentIds,
                selectAllKind: selectAllKind,
                isSelected
            });

            // Fetch the updated merge request details
            const updatedMergeRequest = await axios.get(`/api/merge-requests/${id}`);
            setMergeRequestDetail(updatedMergeRequest.data);

            return true;
        } catch (err: any) {
            console.error('Error updating selections:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to update selections',
                life: 3000
            });
            return false;
        }
    };

    // Merge single types - now just updates status and proceeds to next step
    const mergeSingleTypes = async () => {
        if (!id) return;

        try {

            // Update the merge request status
            const success = await updateMergeRequestStatus('MERGED_SINGLES');

            if (success) {
                // Show success message
                toast.current?.show({
                    severity: 'success',
                    summary: 'Single Types Step Completed',
                    detail: 'Single type selections saved successfully.',
                    life: 5000
                });

                // Validate and proceed to next step
                await proceedToNextStep(2);
            }
        } catch (err: any) {
            console.error('Error in single types step:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to complete single types step',
                life: 5000
            });
        } finally {
        }
    };

    // Merge collections - now just updates status and proceeds to next step
    const mergeCollections = async () => {
        if (!id) return;

        try {
            setMergingCollections(true);

            // Update the merge request status
            const success = await updateMergeRequestStatus('MERGED_COLLECTIONS');

            if (success) {
                // Show success message
                toast.current?.show({
                    severity: 'success',
                    summary: 'Collections Step Completed',
                    detail: 'Collection selections saved successfully.',
                    life: 5000
                });

                // Validate and proceed to next step
                await proceedToNextStep(5);
            }
        } catch (err: any) {
            console.error('Error in collections step:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to complete collections step',
                life: 5000
            });
        } finally {
            setMergingCollections(false);
        }
    };

    // Complete merge
    const completeMerge = async () => {
        if (!id) return;

        try {
            setCompleting(true);

            await axios.post(`/api/merge-requests/${id}/complete`);

            // Fetch the updated merge request details
            const updatedMergeRequest = await axios.get(`/api/merge-requests/${id}`);
            setMergeRequestDetail(updatedMergeRequest.data);

            // Show success message
            toast.current?.show({
                severity: 'success',
                summary: 'Merge Completed',
                detail: 'The merge request has been completed successfully.',
                life: 5000
            });
        } catch (err: any) {
            console.error('Error completing merge:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to complete merge request',
                life: 5000
            });
        } finally {
            setCompleting(false);
        }
    };

    // Loading state
    if (loading) {
        return <div className="flex justify-content-center p-5"><ProgressSpinner/></div>;
    }

    // Error state
    if (error) {
        return (
            <div className="container py-4">
                <Toast ref={toast}/>
                <Card className="mb-4">
                    <div className="p-3 bg-danger text-white">
                        <h2 className="m-0">Error</h2>
                    </div>
                    <div className="p-3">
                        <Message severity="error" text={error} className="w-full mb-3"/>
                        <Button label="Back to Merge Requests" icon="pi pi-arrow-left"
                                onClick={() => navigate('/merge-requests')}/>
                    </div>
                </Card>
            </div>
        );
    }

    // No merge request found
    if (!mergeRequestDetail) {
        return (
            <div className="container py-4">
                <Toast ref={toast}/>
                <Card className="mb-4">
                    <div className="p-3 bg-warning text-white">
                        <h2 className="m-0">Not Found</h2>
                    </div>
                    <div className="p-3">
                        <Message severity="warn" text="Merge request not found" className="w-full mb-3"/>
                        <Button label="Back to Merge Requests" icon="pi pi-arrow-left"
                                onClick={() => navigate('/merge-requests')}/>
                    </div>
                </Card>
            </div>
        );
    }

    const status = mergeRequestDetail.mergeRequest.status;
    const isLocked = ['IN_PROGRESS', 'COMPLETED', 'FAILED'].includes(status);

    return (
        <div className="container py-4">
            <Toast ref={toast}/>

            {/* derive lock flag */}
            {(() => {
                return null;
            })()}

            {/* Header */}
            <Card className="mb-4">
                <div className="flex justify-content-between align-items-center p-3 bg-primary text-white">
                    <div>
                        <h2 className="m-0">{mergeRequestDetail.mergeRequest.name}</h2>
                        <p className="m-0 mt-2">{mergeRequestDetail.mergeRequest.description}</p>
                    </div>
                    <Button label="Back to Merge Requests" icon="pi pi-arrow-left"
                            className="p-button-outlined p-button-secondary"
                            onClick={() => navigate('/merge-requests')}/>
                </div>
                <div className="p-3">
                    <div className="grid">
                        <div className="col-12 md:col-6">
                            <div className="flex align-items-center mb-3">
                                <span className="font-bold mr-2">Source Instance:</span>
                                <Tag value={mergeRequestDetail.mergeRequest.sourceInstance.name} severity="info"/>
                            </div>
                            <div className="flex align-items-center mb-3">
                                <span className="font-bold mr-2">Target Instance:</span>
                                <Tag value={mergeRequestDetail.mergeRequest.targetInstance.name} severity="warning"/>
                            </div>
                        </div>
                        <div className="col-12 md:col-6">
                            <div className="flex align-items-center mb-3">
                                <span className="font-bold mr-2">Status:</span>
                                <Tag
                                    value={mergeRequestDetail.mergeRequest.status.replace('_', ' ').toLowerCase()}
                                    severity={
                                        mergeRequestDetail.mergeRequest.status === 'COMPLETED' ? 'success' :
                                            mergeRequestDetail.mergeRequest.status === 'FAILED' ? 'danger' : 'info'
                                    }
                                />
                            </div>
                            <div className="flex align-items-center mb-3">
                                <span className="font-bold mr-2">Created:</span>
                                <span>{new Date(mergeRequestDetail.mergeRequest.createdAt).toLocaleString()}</span>
                            </div>
                        </div>
                    </div>
                </div>
            </Card>

            {/* Pre-steps Actions */}
            {!isLocked && (
                <Card className="mb-4">
                    <div className="p-3">
                        <div className="grid">
                            <div className="col-12 md:col-6">
                                <Card className="h-full">
                                    <div className="flex align-items-center justify-content-between mb-3">
                                        <h3 className="m-0">1. Schema Compatibility</h3>
                                        <Tag
                                            value={mergeRequestDetail.mergeRequest.status !== 'CREATED' ? 'Checked' : 'Pending'}
                                            severity={mergeRequestDetail.mergeRequest.status !== 'CREATED' ? 'success' : 'warning'}
                                        />
                                    </div>
                                    <SchemaCompatibilityStep
                                        schemaCompatible={mergeRequestDetail.mergeRequest.status === 'SCHEMA_CHECKED' || mergeRequestDetail.mergeRequest.status === 'COMPARED' || mergeRequestDetail.mergeRequest.status === 'MERGED_FILES' || mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES' || mergeRequestDetail.mergeRequest.status === 'MERGED_COLLECTIONS' || mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                        checkingSchema={checkingSchema}
                                        checkSchemaCompatibility={checkSchemaCompatibility}
                                    />
                                </Card>
                            </div>
                            <div className="col-12 md:col-6">
                                <Card className="h-full">
                                    <div className="flex align-items-center justify-content-between mb-3">
                                        <h3 className="m-0">2. Content Comparison</h3>
                                        <Tag
                                            value={(mergeRequestDetail.mergeRequest.status === 'COMPARED' || mergeRequestDetail.mergeRequest.status === 'MERGED_FILES' || mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES' || mergeRequestDetail.mergeRequest.status === 'MERGED_COLLECTIONS' || mergeRequestDetail.mergeRequest.status === 'COMPLETED') ? 'Done' : (mergeRequestDetail.mergeRequest.status !== 'CREATED' ? 'Ready' : 'Locked')}
                                            severity={(mergeRequestDetail.mergeRequest.status === 'COMPARED' || mergeRequestDetail.mergeRequest.status === 'MERGED_FILES' || mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES' || mergeRequestDetail.mergeRequest.status === 'MERGED_COLLECTIONS' || mergeRequestDetail.mergeRequest.status === 'COMPLETED') ? 'success' : (mergeRequestDetail.mergeRequest.status !== 'CREATED' ? 'info' : 'warning')}
                                        />
                                    </div>
                                    <ContentComparisonStep
                                        comparingContent={comparingContent}
                                        schemaCompatible={mergeRequestDetail.mergeRequest.status !== 'CREATED'}
                                        compareContent={compareContent}
                                        status={mergeRequestDetail.mergeRequest.status}
                                    />
                                </Card>
                            </div>
                        </div>
                    </div>
                </Card>
            )}

            {/* Stepper */}
            {(!isLocked && ['COMPARED', 'MERGED_FILES', 'MERGED_SINGLES', 'MERGED_COLLECTIONS'].includes(status)) && (
                <Card>
                    <div className="p-3">
                        <Stepper
                            ref={stepperRef}
                            style={{width: '100%'}}
                            orientation="vertical"
                            activeStep={activeStep}
                            linear={false}
                            onChange={(event) => {
                                const index = (event as any).index;
                                setActiveStep(index);
                            }}>

                            {/* Step 3: Merge Files */}
                            <StepperPanel header="Merge Files">
                                <MergeFilesStep
                                    mergeRequestId={mergeRequestDetail.mergeRequest.id}
                                    filesData={mergeRequestDetail.mergeRequestData?.files}
                                    loading={false}
                                    updateAllSelections={updateAllSelections}
                                    selections={mergeRequestDetail.mergeRequestData?.selections || []}
                                    allMergeData={mergeRequestDetail.mergeRequestData!}
                                    onSaved={updateMergeData}
                                />
                                <div className="flex py-4 gap-2 justify-content-between">
                                    <Button
                                        label="Back"
                                        severity="secondary"
                                        icon="pi pi-arrow-left"
                                        disabled={mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                        onClick={() => {
                                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED') {
                                                toast.current?.show({
                                                    severity: 'info',
                                                    summary: 'Merge Completed',
                                                    detail: 'This merge request has been completed and cannot be modified.',
                                                    life: 3000
                                                });
                                                return;
                                            }
                                            stepperRef.current.prevCallback();
                                        }}
                                    />
                                    <Button
                                        label="Next"
                                        icon="pi pi-arrow-right"
                                        iconPos="right"
                                        disabled={mergeRequestDetail.mergeRequest.status !== 'COMPARED' && mergeRequestDetail.mergeRequest.status !== 'MERGED_FILES' && mergeRequestDetail.mergeRequest.status !== 'MERGED_SINGLES' && mergeRequestDetail.mergeRequest.status !== 'MERGED_COLLECTIONS' && mergeRequestDetail.mergeRequest.status !== 'COMPLETED'}
                                        onClick={() => {
                                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED') {
                                                toast.current?.show({
                                                    severity: 'info',
                                                    summary: 'Merge Completed',
                                                    detail: 'This merge request has been completed and cannot be modified.',
                                                    life: 3000
                                                });
                                                return;
                                            }
                                            proceedToNextStep(1).catch(error => {
                                                toast.current?.show({
                                                    severity: 'error',
                                                    summary: 'Error',
                                                    detail: error,
                                                    life: 3000

                                                })
                                            })
                                        }}
                                    />
                                </div>
                            </StepperPanel>

                            {/* Step 4: Merge Single Types */}
                            <StepperPanel header="Merge Single Types">
                                {(() => {

                                    const arrayTypes: Record<string, ContentTypeComparisonResultWithRelationships[]> = mergeRequestDetail.mergeRequestData?.singleTypes ? Object.entries(mergeRequestDetail.mergeRequestData?.singleTypes).reduce(
                                        (acc, [key, value]) => {
                                            acc[key] = [value];
                                            return acc;
                                        },
                                        {} as Record<string, ContentTypeComparisonResultWithRelationships[]>
                                    ) : {} as Record<string, ContentTypeComparisonResultWithRelationships[]>

                                    return <MergeSingleTypesStep
                                        kind={StrapiContentTypeKind.SingleType}
                                        status={mergeRequestDetail.mergeRequest.status}
                                        mergeSingleTypes={mergeSingleTypes}
                                        mergeRequestId={parseInt(id || '0')}
                                        contentData={arrayTypes}
                                        selections={mergeRequestDetail.mergeRequestData?.selections || []}
                                        loading={false}
                                        allMergeData={mergeRequestDetail.mergeRequestData!}
                                        updateAllSelections={updateAllSelections}
                                        onSaved={updateMergeData}
                                    />
                                })()}
                                <div className="flex py-4 gap-2 justify-content-between">
                                    <Button
                                        label="Back"
                                        severity="secondary"
                                        icon="pi pi-arrow-left"
                                        disabled={mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                        onClick={() => {
                                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED') {
                                                toast.current?.show({
                                                    severity: 'info',
                                                    summary: 'Merge Completed',
                                                    detail: 'This merge request has been completed and cannot be modified.',
                                                    life: 3000
                                                });
                                                return;
                                            }
                                            stepperRef.current.prevCallback();
                                        }}
                                    />
                                    <Button
                                        label="Next"
                                        icon="pi pi-arrow-right"
                                        iconPos="right"
                                        disabled={mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                        onClick={() => {
                                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED') {
                                                toast.current?.show({
                                                    severity: 'info',
                                                    summary: 'Merge Completed',
                                                    detail: 'This merge request has been completed and cannot be modified.',
                                                    life: 3000
                                                });
                                                return;
                                            }
                                            proceedToNextStep(2).catch(error => {
                                                toast.current?.show({
                                                    severity: 'error',
                                                    summary: 'Error',
                                                    detail: error,
                                                    life: 3000

                                                })
                                            })
                                        }}
                                    />
                                </div>
                            </StepperPanel>

                            {/* Step 5: Merge Collections */}
                            <StepperPanel header="Merge Collections">
                                {mergeRequestDetail.mergeRequestData && (
                                    <MergeCollectionsStep
                                        status={mergeRequestDetail.mergeRequest.status}
                                        mergingCollections={mergingCollections}
                                        mergeCollections={mergeCollections}
                                        mergeRequestId={parseInt(id || '0')}
                                        collectionTypesData={mergeRequestDetail.mergeRequestData?.collectionTypes}
                                        selections={mergeRequestDetail.mergeRequestData?.selections}
                                        allMergeData={mergeRequestDetail.mergeRequestData!}
                                        updateAllSelections={updateAllSelections}
                                        onSaved={updateMergeData}
                                    />
                                )}
                                <div className="flex py-4 gap-2 justify-content-between">
                                    <Button
                                        label="Back"
                                        severity="secondary"
                                        icon="pi pi-arrow-left"
                                        disabled={mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                        onClick={() => {
                                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED') {
                                                toast.current?.show({
                                                    severity: 'info',
                                                    summary: 'Merge Completed',
                                                    detail: 'This merge request has been completed and cannot be modified.',
                                                    life: 3000
                                                });
                                                return;
                                            }
                                            stepperRef.current.prevCallback();
                                        }}
                                    />
                                    <Button
                                        label="Next"
                                        icon="pi pi-arrow-right"
                                        iconPos="right"
                                        disabled={mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                        onClick={() => {
                                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED') {
                                                toast.current?.show({
                                                    severity: 'info',
                                                    summary: 'Merge Completed',
                                                    detail: 'This merge request has been completed and cannot be modified.',
                                                    life: 3000
                                                });
                                                return;
                                            }
                                            proceedToNextStep(2).catch(error => {
                                                toast.current?.show({
                                                    severity: 'error',
                                                    summary: 'Error',
                                                    detail: error,
                                                    life: 3000

                                                })
                                            })
                                        }}
                                    />
                                </div>
                            </StepperPanel>

                        </Stepper>
                    </div>
                </Card>
            )}

            {(['MERGED_COLLECTIONS', 'IN_PROGRESS', 'COMPLETED', 'FAILED'].includes(status)) && (
                <Card className="mt-4">
                    <div className="p-3">
                        <CompleteMergeStep
                            status={mergeRequestDetail.mergeRequest.status}
                            completing={completing}
                            completeMerge={completeMerge}
                            selections={mergeRequestDetail.mergeRequestData?.selections}
                            allMergeData={mergeRequestDetail.mergeRequestData}
                        />
                    </div>
                </Card>
            )}
        </div>
    );
};

export default MergeRequestDetails;
