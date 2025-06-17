/**
 * Utility functions for handling attributes in entries
 */

/**
 * Extract representative attributes from an entry
 * This function is used by both MergeCollectionsStep and MergeSingleTypesStep
 */
export const getRepresentativeAttributes = (entry: any, maxAttributes: number = 3): { key: string, value: string }[] => {
    if (!entry) return [];

    // If entry is a StrapiContent, use its rawData
    const entryData = entry.rawData ? entry.rawData : entry;

    // Technical attributes to exclude
    const technicalAttributes = [
        'id', 'documentId', 'createdAt', 'updatedAt', 'publishedAt', 'createdBy', 'updatedBy',
        'locale', 'localizations', 'hash', 'ext', 'mime', 'size', 'url', 'provider', 'previewUrl'
    ];

    // Get all keys from the entry
    const keys = Object.keys(entryData);

    // Filter out technical attributes and get only primitive types (no arrays or objects)
    const representativeKeys = keys
        .filter(key => {
            const value = entryData[key];
            return !technicalAttributes.includes(key) &&
                value !== null &&
                value !== undefined &&
                (typeof value !== 'object' || value === null);
        })
        .slice(0, maxAttributes);

    // Map keys to { key, value } pairs
    return representativeKeys.map(key => {
        let value = entryData[key];

        // Handle string values (truncate long strings)
        if (typeof value === 'string') {
            // Truncate long strings
            value = value.length > 50 ? value.substring(0, 47) + '...' : value;
        }

        return {key, value: String(value)};
    });
};