// Common types used across the application

// JSON types
export interface JsonObject {
    [key: string]: any;
}

// Layout types
export interface LayoutState {
    staticMenuDesktopInactive: boolean;
    overlayMenuActive: boolean;
    profileSidebarVisible: boolean;
    configSidebarVisible: boolean;
    staticMenuMobileActive: boolean;
    menuHoverActive: boolean;
}

export interface LayoutConfig {
    ripple: boolean;
    inputStyle: string;
    menuMode: string;
    colorScheme: string;
    theme: string;
    scale: number;
}

export interface LayoutContextProps {
    layoutConfig: LayoutConfig;
    setLayoutConfig: React.Dispatch<React.SetStateAction<LayoutConfig>>;
    layoutState: LayoutState;
    setLayoutState: React.Dispatch<React.SetStateAction<LayoutState>>;
    onMenuToggle: () => void;
    showProfileSidebar: () => void;
}

export interface ChildContainerProps {
    children: React.ReactNode;
}

export interface StrapiInstance {
    id: number;
    name: string;
    url: string;
    username: string;
    password?: string; // Optional: only present when fetched from /{id}/full endpoint
    apiKey?: string;   // Optional: only present when fetched from /{id}/full endpoint
}

export interface FormData {
    id?: number;
    name: string;
    url: string;
    username: string;
    password: string;  // Can be empty when editing (id is present)
    apiKey: string;    // Can be empty when editing (id is present)
}

// Enums for content type kinds and comparison results
export enum StrapiContentTypeKind {
    SingleType = "singleType",
    CollectionType = "collectionType"
}

export enum ContentTypeComparisonResultKind {
    ONLY_IN_SOURCE = "ONLY_IN_SOURCE",
    ONLY_IN_TARGET = "ONLY_IN_TARGET",
    DIFFERENT = "DIFFERENT",
    IDENTICAL = "IDENTICAL"
}

// File-related types for merge requests
export interface StrapiImageMetadata {
    id: number;
    documentId: string;
    alternativeText: string | null;
    caption: string | null;
    name: string;
    hash: string;
    ext: string;
    mime: string;
    size: number;
    url: string;
    previewUrl: string | null;
    provider: string;
    folderPath: string;
    locale: string | null;
}

export interface StrapiImage {
    metadata: StrapiImageMetadata;
    rawData: JsonObject;
}

export interface StrapiContentMetadata {
    id: number;
    documentId: string;
}

export interface StrapiContent {
    metadata: StrapiContentMetadata;
    rawData: JsonObject;
    cleanData: JsonObject;
}

export interface DifferentFile {
    source: StrapiImage;
    target: StrapiImage;
}

export interface ContentTypeFileComparisonResult {
    onlyInSource: StrapiImage[];
    onlyInTarget: StrapiImage[];
    different: DifferentFile[];
    identical: StrapiImage[];
    contentTypeExists: boolean;
}

// Content type comparison and relationship types
export interface DifferentEntry {
    source: StrapiContent;
    target: StrapiContent;
}

export interface EntryRelationship {
    sourceContentType: string;
    sourceDocumentId: string;
    sourceField: string;
    targetContentType: string;
    targetDocumentId: string;
    targetField?: string;
    relationType: string;
}

export interface ContentRelationship {
    sourceContentType: string;
    sourceField: string;
    targetContentType: string;
    targetField?: string;
    relationType: string;
    isBidirectional: boolean;
}

export interface ContentTypeComparisonResultWithRelationships {
    contentType: string;
    onlyInSource: StrapiContent | null;
    onlyInTarget: StrapiContent | null;
    different: DifferentEntry | null;
    identical: StrapiContent | null;
    kind: StrapiContentTypeKind;
    compareKind: ContentTypeComparisonResultKind;
    relationships: EntryRelationship[];
    dependsOn: string[];
    dependedOnBy: string[];
}

export interface ContentTypesComparisonResultWithRelationships {
    contentType: string;
    onlyInSource: StrapiContent[];
    onlyInTarget: StrapiContent[];
    different: DifferentEntry[];
    identical: StrapiContent[];
    kind: StrapiContentTypeKind;
    relationships: Record<string, EntryRelationship[]>;
    dependsOn: string[];
    dependedOnBy: string[];
}

export interface SelectionStatusInfo {
    documentId: string;
    syncSuccess: boolean | null;
    syncFailureResponse: string | null;
    syncDate: string | null;
}

export interface MergeRequestSelectionDTO {
    contentType: string;
    entriesToCreate: string[];
    entriesToUpdate: string[];
    entriesToDelete: string[];
    createStatus?: SelectionStatusInfo[];
    updateStatus?: SelectionStatusInfo[];
    deleteStatus?: SelectionStatusInfo[];
}

export interface SelectedContentTypeDependency {
    contentType: string;
    documentId: string;
    relationshipPath?: EntryRelationship[];
}

// Main data structure for merge requests
export interface MergeRequestData {
    files: ContentTypeFileComparisonResult;
    singleTypes: Record<string, ContentTypeComparisonResultWithRelationships>;
    collectionTypes: Record<string, ContentTypesComparisonResultWithRelationships>;
    contentTypeRelationships: ContentRelationship[];
    selections: MergeRequestSelectionDTO[];
}

// Combined structure returned by the API
export interface MergeRequestDetail {
    mergeRequest: {
        id: number;
        name: string;
        description: string;
        sourceInstance: StrapiInstance;
        targetInstance: StrapiInstance;
        status: string;
        createdAt: string;
        updatedAt: string;
    };
    mergeRequestData?: MergeRequestData;
}
