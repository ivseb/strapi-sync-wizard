import React from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';

interface SchemaCompatibilityStepProps {
    schemaCompatible: boolean | null;
    checkingSchema: boolean;
    checkSchemaCompatibility: (force?: boolean) => void;
    blocking?: string[];
    warnings?: string[];
}

const SchemaCompatibilityStep: React.FC<SchemaCompatibilityStepProps> = ({
                                                                             schemaCompatible,
                                                                             checkingSchema,
                                                                             checkSchemaCompatibility,
                                                                             blocking = [],
                                                                             warnings = []
                                                                         }) => {

    return (
        <div>
            <h3>Schema Compatibility Check</h3>
            <p>
                The merge only needs the schemas to be <strong>compatible</strong> (not identical):
                every source content type must exist in the target with matching field types.
                Other differences are tolerated — source-only fields are dropped automatically and
                target-only fields are left untouched.
            </p>

            <div className="flex flex-column align-items-center my-5">
                {schemaCompatible !== null && (
                    <div className="mb-3 w-full" style={{ maxWidth: 640 }}>
                        <Message
                            severity={schemaCompatible ? "success" : "error"}
                            text={schemaCompatible
                                ? "The schemas are compatible — you can proceed."
                                : "The schemas are not compatible. Resolve the blocking issues below."}
                            className="w-full"
                        />
                    </div>
                )}

                {blocking.length > 0 && (
                    <div className="mb-3 w-full" style={{ maxWidth: 640 }}>
                        <h4 style={{ margin: '0 0 .4rem', color: 'var(--red-400)' }}>
                            Blocking ({blocking.length})
                        </h4>
                        <ul style={{ margin: 0, paddingLeft: '1.2rem' }}>
                            {blocking.map((b, i) => <li key={i} style={{ marginBottom: 4 }}>{b}</li>)}
                        </ul>
                    </div>
                )}

                {warnings.length > 0 && (
                    <div className="mb-3 w-full" style={{ maxWidth: 640 }}>
                        <h4 style={{ margin: '0 0 .4rem', color: 'var(--yellow-500)' }}>
                            Warnings ({warnings.length})
                        </h4>
                        <ul style={{ margin: 0, paddingLeft: '1.2rem', opacity: .9 }}>
                            {warnings.map((w, i) => <li key={i} style={{ marginBottom: 4 }}>{w}</li>)}
                        </ul>
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
