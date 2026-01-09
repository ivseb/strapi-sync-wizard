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
    isVirtual?: boolean;
    // Optional DB connection metadata (secure list includes these except passwords)
    dbHost?: string | null;
    dbPort?: number | null;
    dbName?: string | null;
    dbSchema?: string | null;
    dbUser?: string | null;
    dbSslMode?: string | null;
    // Sensitive fields present only when fetched from /{id}/full endpoint
    password?: string; // Strapi admin password
    apiKey?: string;   // Strapi API key
    dbPassword?: string | null; // DB password (only in full endpoint)
}

export interface FormData {
    id?: number;
    name: string;
    url: string;
    username: string;
    password: string;  // Can be empty when editing (id is present)
    apiKey: string;    // Can be empty when editing (id is present)
    isVirtual?: boolean; // virtual placeholder instance (relaxes validation)
    // DB connection fields
    dbHost?: string;
    dbPort?: number | null; // store as number for payload; null if empty
    dbName?: string;
    dbSchema?: string;
    dbUser?: string;
    dbPassword?: string; // optional; when editing leave empty to keep
    dbSslMode?: string;
}

// Enums for content type kinds and comparison results
export enum StrapiContentTypeKind {
    SingleType = "singleType",
    CollectionType = "collectionType",
    Files = "files"
}

export enum ContentTypeComparisonResultKind {
    ONLY_IN_SOURCE = "ONLY_IN_SOURCE",
    ONLY_IN_TARGET = "ONLY_IN_TARGET",
    DIFFERENT = "DIFFERENT",
    IDENTICAL = "IDENTICAL",
    EXCLUDED = "EXCLUDED"
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
    folder?: string;
    locale: string | null;
    updatedAt: string;
}

export interface StrapiImage {
    metadata: StrapiImageMetadata;
    rawData: JsonObject;
}

export interface StrapiContentMetadata {
    id: number | null;
    documentId: string;
    locale?: string | null;
}

export interface StrapiLinkRef {
    field: string;
    targetTable: string;
    targetId: number | null;
    order?: number | null;
    id?: number | null;
    lnkTable?: string | null;
}

export interface StrapiContent {
    metadata: StrapiContentMetadata;
    rawData: JsonObject;
    cleanData: JsonObject;
    links: StrapiLinkRef[];
}

export interface DifferentFile {
    source: StrapiImage;
    target: StrapiImage;
}

export interface ContentTypeFileComparisonResult {
    id: string;
    sourceImage: StrapiImage | null;
    targetImage: StrapiImage | null;
    compareState: ContentTypeComparisonResultKind;
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
    compareStatus: ContentTypeComparisonResultKind | null;
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
    id: string; // unique id used for selection
    tableName: string;
    contentType: string; // UID
    sourceContent?: StrapiContent | null;
    targetContent?: StrapiContent | null;
    compareState: ContentTypeComparisonResultKind;
    kind: StrapiContentTypeKind;
}


export type Direction = 'TO_CREATE' | 'TO_UPDATE' | 'TO_DELETE';

export interface MergeRequestSelection {
    id: number;
    mergeRequestId: number;
    tableName: string;
    documentId: string;
    direction: Direction;
    createdAt: string;
    syncSuccess?: boolean | null;
    syncFailureResponse?: string | null;
    syncDate?: string | null;
}

export interface MergeRequestSelectionDTO {
    tableName: string;
    selections: MergeRequestSelection[];
}

export interface SelectedContentTypeDependency {
    contentType: string;
    documentId: string;
    relationshipPath: EntryRelationship[];
}

export interface ManualMappingsResponseDTO {
    success: boolean;
    data?: MergeRequestData;
    message?: string
}

// Main data structure for merge requests
export interface MergeRequestData {

    files: ContentTypeFileComparisonResult[];
    singleTypes: Record<string, ContentTypeComparisonResultWithRelationships>;
    collectionTypes: Record<string, ContentTypeComparisonResultWithRelationships[]>;
    contentTypeRelationships: ContentRelationship[];
    selections: MergeRequestSelectionDTO[];
}

// Combined structure returned by the API
export interface MergeRequestDetail {
    isCompatible:boolean;
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

// Sync plan DTOs
export interface SyncPlanItemDTO {
    tableName: string;
    documentId: string;
    direction: Direction;
}

export interface MissingDependencyDTO {
    fromTable: string;
    fromDocumentId: string;
    linkField: string;
    linkTargetTable: string;
    reason: string;
}

export interface CircularDependencyEdgeDTO {
    fromTable: string;
    fromDocumentId: string;
    toTable: string;
    toDocumentId: string;
    viaField: string;
}

export interface DependencyEdgeDTO {
    fromTable: string;
    fromDocumentId: string;
    toTable: string;
    toDocumentId: string;
    viaField: string;
}

export interface SyncPlanDTO {
    batches: SyncPlanItemDTO[][];
    missingDependencies: MissingDependencyDTO[];
    circularEdges: CircularDependencyEdgeDTO[];
    edges: DependencyEdgeDTO[];
}
