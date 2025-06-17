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
import {MergeRequestDetail} from '../types';

// Step Components
import SchemaCompatibilityStep from './steps/SchemaCompatibilityStep';
import ContentComparisonStep from './steps/ContentComparisonStep';
import MergeFilesStep from './steps/MergeFilesStep';
import MergeSingleTypesStep from './steps/MergeSingleTypesStep';
import MergeCollectionsStep from './steps/MergeCollectionsStep';
import CompleteMergeStep from './steps/CompleteMergeStep';

const MergeRequestDetails: React.FC = () => {
    const {id} = useParams<{ id: string }>();
    const navigate = useNavigate();
    const toast = useRef<Toast>(null);

    const [mergeRequestDetail, setMergeRequestDetail] = useState<MergeRequestDetail | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [activeStep, setActiveStep] = useState<number>(0);
    const [checkingSchema, setCheckingSchema] = useState<boolean>(false);
    const [comparingContent, setComparingContent] = useState<boolean>(false);
    const [mergingSingles, setMergingSingles] = useState<boolean>(false);
    const [mergingCollections, setMergingCollections] = useState<boolean>(false);
    const [completing, setCompleting] = useState<boolean>(false);

    // Create a ref for the stepper
    const stepperRef = useRef<any>(null);


    // No need for a separate function to fetch merge data as it's now included in the main API response

    // Fetch merge request details
    useEffect(() => {
        const fetchMergeRequest = async () => {
            try {
                setLoading(true);

                const response = await axios.get(`/api/merge-requests/${id}`);
                setMergeRequestDetail(response.data);

                // Set active step based on status
                let stepIndex = 0;
                switch (response.data.mergeRequest.status) {
                    case 'SCHEMA_CHECKED':
                        stepIndex = 0;
                        break;
                    case 'COMPARED':
                    case 'MERGED_FILES':
                    case 'MERGED_SINGLES':
                    case 'MERGED_COLLECTIONS':
                        // Set the appropriate step index
                        if (response.data.mergeRequest.status === 'COMPARED') {
                            stepIndex = 1;
                        } else if (response.data.mergeRequest.status === 'MERGED_FILES') {
                            stepIndex = 2;
                        } else if (response.data.mergeRequest.status === 'MERGED_SINGLES') {
                            stepIndex = 3;
                        } else {
                            stepIndex = 4;
                        }
                        break;
                    case 'COMPLETED':
                        stepIndex = 5;
                        break;
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

            // Move to the next step if compatible
            if (isCompatible) {
                setActiveStep(1);
                if (stepperRef.current) {
                    stepperRef.current.nextCallback();
                }
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

            // Call the compare endpoint - it no longer returns data
            await axios.post(`/api/merge-requests/${id}/compare?force=true`);

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

            // Move to the next step
            setActiveStep(2);
            if (stepperRef.current) {
                stepperRef.current.nextCallback();
            }
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

            if (nextStep === 3 && mergeRequestDetail.mergeRequest.status === 'COMPARED') {
                newStatus = 'MERGED_FILES';
                await updateMergeRequestStatus(newStatus);
            } else if (nextStep === 4 && mergeRequestDetail.mergeRequest.status === 'MERGED_FILES') {
                newStatus = 'MERGED_SINGLES';
                await updateMergeRequestStatus(newStatus);
            } else if (nextStep === 5 && mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES') {
                newStatus = 'MERGED_COLLECTIONS';
                await updateMergeRequestStatus(newStatus);
            }
        }

        setActiveStep(nextStep);
        if (stepperRef.current) {
            stepperRef.current.nextCallback();
        }
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

    // Function to update a single selection
    const updateSingleSelection = async (contentType: string, documentId: string, direction: string, isSelected: boolean) => {
        if (!id) return false;

        try {
            await axios.post(`/api/merge-requests/${id}/selection`, {
                contentType,
                documentId,
                direction,
                isSelected
            });

            // Fetch the updated merge request details
            const updatedMergeRequest = await axios.get(`/api/merge-requests/${id}`);
            setMergeRequestDetail(updatedMergeRequest.data);

            return true;
        } catch (err: any) {
            console.error('Error updating selection:', err);
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: err.response?.data?.message || 'Failed to update selection',
                life: 3000
            });
            return false;
        }
    };

    // Merge single types - now just updates status and proceeds to next step
    const mergeSingleTypes = async () => {
        if (!id) return;

        try {
            setMergingSingles(true);

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
                await proceedToNextStep(4);
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
            setMergingSingles(false);
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

    return (
        <div className="container py-4">
            <Toast ref={toast}/>

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

            {/* Stepper */}
            <Card>
                <div className="p-3">
                    <Stepper 
                        ref={stepperRef} 
                        style={{width: '100%'}} 
                        orientation="vertical"
                        activeStep={activeStep}
                        linear={true}
                        onChange={(event) => {
                            // Access the step index from the event
                            const index = (event as any).index;

                            // Prevent navigation to steps after schema compatibility if schema check hasn't been performed
                            if (index > 0 && mergeRequestDetail.mergeRequest.status === 'CREATED') {
                                (event as any).preventDefault();
                                toast.current?.show({
                                    severity: 'warn',
                                    summary: 'Schema Check Required',
                                    detail: 'You must check schema compatibility before proceeding to the next steps.',
                                    life: 3000
                                });
                                return;
                            }

                            // Prevent navigation to steps after content comparison if comparison hasn't been performed
                            if (index > 1 && mergeRequestDetail.mergeRequest.status === 'SCHEMA_CHECKED') {
                                (event as any).preventDefault();
                                toast.current?.show({
                                    severity: 'warn',
                                    summary: 'Content Comparison Required',
                                    detail: 'You must compare content before proceeding to the next steps.',
                                    life: 3000
                                });
                                return;
                            }

                            // Prevent navigation to any step if merge is completed
                            if (mergeRequestDetail.mergeRequest.status === 'COMPLETED' && index !== activeStep) {
                                (event as any).preventDefault();
                                toast.current?.show({
                                    severity: 'info',
                                    summary: 'Merge Completed',
                                    detail: 'This merge request has been completed and cannot be modified.',
                                    life: 3000
                                });
                                return;
                            }

                            // Allow navigation between merge steps (2-5)
                            if (index >= 2 && index <= 5 && 
                                (mergeRequestDetail.mergeRequest.status === 'COMPARED' || 
                                 mergeRequestDetail.mergeRequest.status === 'MERGED_FILES' || 
                                 mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES' || 
                                 mergeRequestDetail.mergeRequest.status === 'MERGED_COLLECTIONS')) {
                                setActiveStep(index);
                                return;
                            }
                        }}>
                        {/* Step 1: Schema Compatibility */}
                        <StepperPanel header="Schema Compatibility">
                            <SchemaCompatibilityStep
                                schemaCompatible={mergeRequestDetail.mergeRequest.status === 'SCHEMA_CHECKED' || mergeRequestDetail.mergeRequest.status === 'COMPARED' || mergeRequestDetail.mergeRequest.status === 'MERGED_FILES' || mergeRequestDetail.mergeRequest.status === 'MERGED_SINGLES' || mergeRequestDetail.mergeRequest.status === 'MERGED_COLLECTIONS' || mergeRequestDetail.mergeRequest.status === 'COMPLETED'}
                                checkingSchema={checkingSchema}
                                checkSchemaCompatibility={checkSchemaCompatibility}
                            />
                            <div className="flex py-4 justify-content-end">
                                <Button
                                    label="Next"
                                    icon="pi pi-arrow-right"
                                    iconPos="right"
                                    disabled={mergeRequestDetail.mergeRequest.status === 'CREATED'}
                                    onClick={() => stepperRef.current.nextCallback()}
                                />
                            </div>
                        </StepperPanel>

                        {/* Step 2: Content Comparison */}
                        <StepperPanel header="Content Comparison">
                            <ContentComparisonStep
                                comparingContent={comparingContent}
                                schemaCompatible={mergeRequestDetail.mergeRequest.status !== 'CREATED'}
                                compareContent={compareContent}
                                status={mergeRequestDetail.mergeRequest.status}
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
                                    disabled={mergeRequestDetail.mergeRequest.status !== 'COMPARED' &&
                                        mergeRequestDetail.mergeRequest.status !== 'MERGED_FILES' &&
                                        mergeRequestDetail.mergeRequest.status !== 'MERGED_SINGLES' &&
                                        mergeRequestDetail.mergeRequest.status !== 'MERGED_COLLECTIONS' &&
                                        mergeRequestDetail.mergeRequest.status !== 'COMPLETED'}
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
                                        stepperRef.current.nextCallback();
                                    }}
                                />
                            </div>
                        </StepperPanel>

                        {/* Step 3: Merge Files */}
                        <StepperPanel header="Merge Files">
                            <MergeFilesStep

                                mergeRequestId={mergeRequestDetail.mergeRequest.id}
                                filesData={mergeRequestDetail.mergeRequestData?.files}
                                loading={false}
                                updateSingleSelection={updateSingleSelection}
                                selections={mergeRequestDetail.mergeRequestData?.selections}
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
                                        proceedToNextStep(3);
                                    }}
                                />
                            </div>
                        </StepperPanel>

                        {/* Step 4: Merge Single Types */}
                        <StepperPanel header="Merge Single Types">
                            <MergeSingleTypesStep
                                status={mergeRequestDetail.mergeRequest.status}
                                mergingSingles={mergingSingles}
                                mergeSingleTypes={mergeSingleTypes}
                                mergeRequestId={parseInt(id || '0')}
                                singleTypesData={mergeRequestDetail.mergeRequestData?.singleTypes}
                                selections={mergeRequestDetail.mergeRequestData?.selections}
                                loading={false}
                                allMergeData={mergeRequestDetail.mergeRequestData}
                                updateSingleSelection={updateSingleSelection}
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
                                        proceedToNextStep(4);
                                    }}
                                />
                            </div>
                        </StepperPanel>

                        {/* Step 5: Merge Collections */}
                        <StepperPanel header="Merge Collections">
                            <MergeCollectionsStep
                                status={mergeRequestDetail.mergeRequest.status}
                                mergingCollections={mergingCollections}
                                mergeCollections={mergeCollections}
                                mergeRequestId={parseInt(id || '0')}
                                collectionTypesData={mergeRequestDetail.mergeRequestData?.collectionTypes}
                                selections={mergeRequestDetail.mergeRequestData?.selections}
                                contentTypeRelationships={mergeRequestDetail.mergeRequestData?.contentTypeRelationships}
                                loading={false}
                                allMergeData={mergeRequestDetail.mergeRequestData}
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
                                        proceedToNextStep(5);
                                    }}
                                />
                            </div>
                        </StepperPanel>

                        {/* Step 6: Complete */}
                        <StepperPanel header="Complete">
                            <CompleteMergeStep
                                status={mergeRequestDetail.mergeRequest.status}
                                completing={completing}
                                completeMerge={completeMerge}
                                selections={mergeRequestDetail.mergeRequestData?.selections}
                                allMergeData={mergeRequestDetail.mergeRequestData}
                            />
                            { mergeRequestDetail.mergeRequest.status !== 'COMPLETED' &&
                            <div className="flex py-4">
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
                            </div>
                            }
                        </StepperPanel>
                    </Stepper>
                </div>
            </Card>
        </div>
    );
};

export default MergeRequestDetails;
