import React from 'react';
import {Dialog} from 'primereact/dialog';
import {DiffEditor, Editor} from "@monaco-editor/react";
import {TabView, TabPanel} from 'primereact/tabview';
import {Message} from 'primereact/message';
import {Tag} from 'primereact/tag';
import {Button} from 'primereact/button';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';

interface EditorDialogProps {
    visible: boolean;
    onHide: () => void;
    header: string;
    content: any;
    isDiff: boolean;
    originalContent?: any;
    modifiedContent?: any;
    errorMessage?: string;
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
    modifiedContent,
    errorMessage
}) => {
    // Format JSON for display
    const formatJson = (json: any) => {
        return JSON.stringify(json, null, 2);
    };

    const formattedContent = content ? formatJson(content) : '';
    const formattedOriginal = originalContent ? formatJson(originalContent) : '';
    const formattedModified = modifiedContent ? formatJson(modifiedContent) : '';

    const representative = !isDiff && content ? getRepresentativeAttributes(content as any, 6) : [];

    // Helpers to detect Strapi file objects
    const isStrapiFile = (obj: any): boolean => !!obj && !!obj.metadata && (typeof obj.metadata.mime === 'string' || typeof obj.metadata.url === 'string');
    const isImageMime = (obj: any): boolean => isStrapiFile(obj) && typeof obj.metadata.mime === 'string' && obj.metadata.mime.startsWith('image');
    const fileUrl = (obj: any): string | undefined => (obj && obj.metadata && obj.metadata.url) ? obj.metadata.url : undefined;
    const fileName = (obj: any): string | undefined => (obj && obj.metadata && obj.metadata.name) ? obj.metadata.name : undefined;

    const renderSingleFilePreview = (obj: any) => {
        if (!isStrapiFile(obj)) return (
            <Message severity="info" text="No representative fields available for this item." />
        );
        const url = fileUrl(obj);
        const name = fileName(obj) || obj?.metadata?.documentId || 'file';
        if (isImageMime(obj) && url) {
            return (
                <div className="flex flex-column align-items-start gap-2">
                    <img src={url} alt={name} style={{ maxWidth: '100%', maxHeight: 400, objectFit: 'contain' }} />
                    <div className="flex gap-2">
                        <a href={url} target="_blank" rel="noreferrer">Open image in new tab</a>
                    </div>
                </div>
            );
        }
        if (url) {
            return (
                <div className="flex flex-column align-items-start gap-2">
                    <div>{name}</div>
                    <Button label="View / Download" icon="pi pi-download" className="p-button-text" onClick={() => window.open(url, '_blank')} />
                </div>
            );
        }
        return <Message severity="warn" text="This file has no accessible URL." />;
    };

    const renderDiffFilePreview = (left: any, right: any) => {
        const leftIsFile = isStrapiFile(left);
        const rightIsFile = isStrapiFile(right);
        if (!leftIsFile && !rightIsFile) return null;
        return (
            <div className="p-3" style={{ maxHeight: 'calc(80vh - 10rem)', overflowY: 'auto' }}>
                <div className="flex flex-row gap-4">
                    <div className="flex-1">
                        <Tag severity="info" value="Source" className="mb-2" />
                        {leftIsFile ? renderSingleFilePreview(left) : <Message severity="info" text="No source file" />}
                    </div>
                    <div className="flex-1">
                        <Tag severity="warning" value="Target" className="mb-2" />
                        {rightIsFile ? renderSingleFilePreview(right) : <Message severity="info" text="No target file" />}
                    </div>
                </div>
            </div>
        );
    };

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
                    isStrapiFile(originalContent) || isStrapiFile(modifiedContent) ? (
                        <TabView>
                            <TabPanel header="Preview">
                                {renderDiffFilePreview(originalContent, modifiedContent)}
                            </TabPanel>
                            <TabPanel header="JSON Diff">
                                <DiffEditor
                                    height="60vh"
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
                            </TabPanel>
                        </TabView>
                    ) : (
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
                    )
                ) : (
                    <TabView>
                        <TabPanel header="Summary">
                            <div className="p-3" style={{ maxHeight: 'calc(80vh - 10rem)', overflowY: 'auto' }}>
                                {errorMessage && (
                                    <div className="mb-3">
                                        <Message severity="error" text={errorMessage} />
                                    </div>
                                )}
                                {isStrapiFile(content) ? (
                                    renderSingleFilePreview(content)
                                ) : representative.length > 0 ? (
                                    <div>
                                        {representative.map((attr, idx) => (
                                            <div key={idx} className="mb-2">
                                                <span className="font-bold mr-2">{attr.key}:</span>
                                                <span>{attr.value}</span>
                                            </div>
                                        ))}
                                    </div>
                                ) : (
                                    <Message severity="info" text="No representative fields available for this item." />
                                )}
                            </div>
                        </TabPanel>
                        <TabPanel header="JSON">
                            <div style={{height: 'calc(73vh - 5rem)', width: '100%'}}>
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
                            </div>
                        </TabPanel>
                    </TabView>
                )}
            </div>
        </Dialog>
    );
};

export default EditorDialog;