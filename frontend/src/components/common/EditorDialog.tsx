import React, { useMemo, useState } from 'react';
import {Dialog} from 'primereact/dialog';
import {DiffEditor, Editor} from "@monaco-editor/react";
import {TabView, TabPanel} from 'primereact/tabview';
import {Accordion, AccordionTab} from 'primereact/accordion';
import {Message} from 'primereact/message';
import {Tag} from 'primereact/tag';
import {Button} from 'primereact/button';
import {InputText} from 'primereact/inputtext';
import {Tree} from 'primereact/tree';
import {TreeNode} from 'primereact/treenode';
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
    onExcludePath?: (path: string) => void;
    httpLogs?: {fileName: string, content: string, timestamp: string}[];
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
    errorMessage,
    onExcludePath,
    httpLogs
}) => {
    const [excludePathInput, setExcludePathInput] = useState('');
    const [showPathPicker, setShowPathPicker] = useState(false);

    // Format JSON for display
    const formatJson = (json: any) => {
        return JSON.stringify(json, null, 2);
    };

    // Helper to convert JSON to TreeNodes for path picking
    const jsonToTreeNodes = (obj: any, path: string = ''): TreeNode[] => {
        if (typeof obj !== 'object' || obj === null) return [];

        const entries = Array.isArray(obj) ? (obj.length > 0 ? Object.entries(obj[0]) : []) : Object.entries(obj);

        return entries
            .filter(([key]) => !['__links', '__order', 'id', 'documentId', 'document_id'].includes(key)) // Hide internal/technical fields
            .map(([key, value]) => {
                const currentPath = path ? `${path}.${key}` : key;
                const isObject = typeof value === 'object' && value !== null;
                const isArray = Array.isArray(value);

                return {
                    key: currentPath,
                    label: key,
                    data: currentPath,
                    children: isObject ? jsonToTreeNodes(isArray ? (value.length > 0 ? value[0] : {}) : value, currentPath) : undefined,
                    icon: isObject ? (isArray ? 'pi pi-list' : 'pi pi-folder') : 'pi pi-tag',
                    selectable: true
                };
            });
    };

    const treeNodes = useMemo(() => {
        const dataToExplore = originalContent || content;
        return dataToExplore ? jsonToTreeNodes(dataToExplore) : [];
    }, [originalContent, content]);

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

    const renderHttpLogs = () => {
        if (!httpLogs || httpLogs.length === 0) return <Message severity="info" text="No HTTP logs available for this item." />;

        return (
            <div className="overflow-auto" style={{ maxHeight: 'calc(80vh - 10rem)' }}>
                <Accordion multiple={true}>
                    {httpLogs.map((log, idx) => (
                        <AccordionTab key={idx} header={log.fileName.replace('.http', '')}>
                            <div style={{height: '400px', width: '100%'}}>
                                <Editor
                                    defaultLanguage="http"
                                    keepCurrentModel={true}
                                    defaultValue={log.content}
                                    options={{
                                        readOnly: true,
                                        minimap: {enabled: false},
                                        scrollBeyondLastLine: false,
                                        wordWrap: 'on'
                                    }}
                                />
                            </div>
                        </AccordionTab>
                    ))}
                </Accordion>
            </div>
        );
    };

    return (
        <Dialog
            header={header}
            visible={visible}
            style={{width: '80vw', height: '80vh'}}
            onHide={() => {
                setExcludePathInput('');
                setShowPathPicker(false);
                onHide();
            }}
            maximizable
        >
            <div className="flex flex-column" style={{height: '100%'}}>
                {onExcludePath && (
                    <div className="flex flex-column mb-2 p-2 bg-gray-100 border-round">
                        <div className="flex align-items-center gap-2 mb-1">
                            <i className="pi pi-ban text-warning"></i>
                            <span className="text-sm font-bold">Vincola Campo:</span>
                            <InputText 
                                value={excludePathInput} 
                                onChange={(e) => setExcludePathInput(e.target.value)} 
                                placeholder="es. metadata.name o rawData.title" 
                                className="p-inputtext-sm flex-1"
                            />
                            <Button 
                                icon="pi pi-search" 
                                className="p-button-sm p-button-text p-button-secondary" 
                                tooltip="Seleziona path dall'albero"
                                onClick={() => setShowPathPicker(!showPathPicker)}
                            />
                            <Button 
                                label="Escludi" 
                                icon="pi pi-plus" 
                                className="p-button-sm p-button-warning" 
                                onClick={() => {
                                    if (excludePathInput) {
                                        onExcludePath(excludePathInput);
                                        setExcludePathInput('');
                                        setShowPathPicker(false);
                                    }
                                }}
                                disabled={!excludePathInput}
                            />
                        </div>
                        {showPathPicker && (
                            <div className="border-1 border-300 border-round p-2 mt-1 bg-white overflow-auto" style={{ maxHeight: '200px' }}>
                                <div className="text-xs text-500 mb-2">Clicca su una chiave per selezionare il path completo:</div>
                                <Tree 
                                    value={treeNodes} 
                                    selectionMode="single" 
                                    onSelect={(e) => {
                                        setExcludePathInput(e.node.data as string);
                                        // Non chiudiamo automaticamente per permettere di cambiare idea
                                    }}
                                    className="p-0 border-none text-sm"
                                />
                            </div>
                        )}
                    </div>
                )}
                <div style={{flex: 1, minHeight: 0}}>
                    <TabView>
                        <TabPanel header={isDiff ? "Differences" : "Content"}>
                            {isDiff ? (
                                isStrapiFile(originalContent) || isStrapiFile(modifiedContent) ? (
                                    <TabView>
                                        <TabPanel header="Preview">
                                            {renderDiffFilePreview(originalContent, modifiedContent)}
                                        </TabPanel>
                                        <TabPanel header="JSON Diff">
                                            <div className="flex flex-row mb-2">
                                                <div className="flex-1 font-bold text-center">Source</div>
                                                <div className="flex-1 font-bold text-center">Destination</div>
                                            </div>
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
                                    <div className="flex flex-column h-full">
                                        <div className="flex flex-row mb-2">
                                            <div className="flex-1 font-bold text-center">Source</div>
                                            <div className="flex-1 font-bold text-center">Destination</div>
                                        </div>
                                        <div style={{flex: 1, minHeight: 0}}>
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
                                        </div>
                                    </div>
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
                        </TabPanel>
                        <TabPanel header="HTTP Logs">
                            {renderHttpLogs()}
                        </TabPanel>
                    </TabView>
                </div>
            </div>
        </Dialog>
    );
};

export default EditorDialog;