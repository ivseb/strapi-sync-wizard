import React from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';

interface SchemaCompatibilityStepProps {
    schemaCompatible: boolean | null;
    checkingSchema: boolean;
    checkSchemaCompatibility: (force?: boolean) => void;
}

const SchemaCompatibilityStep: React.FC<SchemaCompatibilityStepProps> = ({
                                                                             schemaCompatible,
                                                                             checkingSchema,
                                                                             checkSchemaCompatibility
                                                                         }) => {

    return (
        <div>
            <h3>Schema Compatibility Check</h3>
            <p>
                This step checks if the schemas of the source and target instances are compatible.
                Compatible schemas are required for a successful merge.
            </p>

            <div className="flex flex-column align-items-center my-5">
                {schemaCompatible !== null && (
                    <div className="mb-3">
                        <Message
                            severity={schemaCompatible ? "success" : "warn"}
                            text={schemaCompatible
                                ? "The schemas are compatible! You can proceed to the next step."
                                : "The schemas are not compatible. Please ensure both instances have compatible schemas."}
                            className="w-full"
                        />
                    </div>
                )}


                <Button
                    label="Check Schema Compatibility"
                    icon="pi pi-check-circle"
                    loading={checkingSchema}
                    disabled={checkingSchema}
                    onClick={() => checkSchemaCompatibility(true)}
                />
            </div>
        </div>
    );
};

export default SchemaCompatibilityStep;
