import React from 'react';
import {Dialog} from 'primereact/dialog';
import {DiffEditor, Editor} from "@monaco-editor/react";

interface EditorDialogProps {
    visible: boolean;
    onHide: () => void;
    header: string;
    content: any;
    isDiff: boolean;
    originalContent?: any;
    modifiedContent?: any;
}

/**
 * A reusable component for displaying content in a Monaco editor dialog
 * Used in both MergeCollectionsStep and MergeSingleTypesStep
 */
const EditorDialog: React.FC<EditorDialogProps> = ({
    visible,
    onHide,
    header,
    content,
    isDiff,
    originalContent,
    modifiedContent
}) => {
    // Format JSON for display
    const formatJson = (json: any) => {
        return JSON.stringify(json, null, 2);
    };

    const formattedContent = content ? formatJson(content) : '';
    const formattedOriginal = originalContent ? formatJson(originalContent) : '';
    const formattedModified = modifiedContent ? formatJson(modifiedContent) : '';

    return (
        <Dialog
            header={header}
            visible={visible}
            style={{width: '80vw', height: '80vh'}}
            onHide={onHide}
            maximizable
        >
            <div style={{height: 'calc(80vh - 6rem)', width: '100%'}}>
                {isDiff ? (
                    <DiffEditor
                        language="json"
                        keepCurrentModifiedModel={true}
                        keepCurrentOriginalModel={true}
                        original={formattedOriginal}
                        modified={formattedModified}
                        options={{
                            readOnly: true,
                            minimap: {enabled: false},
                            scrollBeyondLastLine: false
                        }}
                    />
                ) : (
                    <Editor
                        defaultLanguage="json"
                        keepCurrentModel={true}
                        defaultValue={formattedContent}
                        options={{
                            readOnly: true,
                            minimap: {enabled: false},
                            scrollBeyondLastLine: false
                        }}
                    />
                )}
            </div>
        </Dialog>
    );
};

export default EditorDialog;