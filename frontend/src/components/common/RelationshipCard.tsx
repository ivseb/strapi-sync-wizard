import React from 'react';
import {
    ContentTypeComparisonResultWithRelationships,
    ContentTypeFileComparisonResult,
    MergeRequestData,
    StrapiContent,
    StrapiLinkRef
} from '../../types';
import { isComparisonItemResolved } from '../../utils/entryUtils';

interface RelationshipCardProps {
    allMergeData: MergeRequestData;
    link: StrapiLinkRef,
    content: ContentTypeComparisonResultWithRelationships | ContentTypeFileComparisonResult;
    index: number;
    identical: boolean;
    getRepresentativeAttributes: (entry: StrapiContent) => { key: string, value: string }[];
}


function ifFileComparisonResult<T>(
    relationship: ContentTypeComparisonResultWithRelationships | ContentTypeFileComparisonResult,
    callback: (file: ContentTypeFileComparisonResult) => T
): T | null {
    return ('sourceImage' in relationship || 'targetImage' in relationship)
        ? callback(relationship as ContentTypeFileComparisonResult)
        : null;
}

function ifContentTypeComparisonResult<T>(
    relationship: ContentTypeComparisonResultWithRelationships | ContentTypeFileComparisonResult,
    callback: (content: ContentTypeComparisonResultWithRelationships) => T
): T | null {
    return ('tableName' in relationship && 'contentType' in relationship)
        ? callback(relationship as ContentTypeComparisonResultWithRelationships)
        : null;
}


/**
 * A reusable component for displaying relationship cards
 * Used in both MergeCollectionsStep and MergeSingleTypesStep
 */
const RelationshipCard: React.FC<RelationshipCardProps> = ({
                                                               allMergeData,
                                                               link,
                                                               content,
                                                               index,
                                                               identical,
                                                               getRepresentativeAttributes
                                                           }) => {
    // Check if it's an image


    const isFile = 'sourceImage' in content || 'targetImage' in content;

    const title = isFile ? 'File' : (content as ContentTypeComparisonResultWithRelationships).tableName;


    const renderFile = () => {
        return ifFileComparisonResult(content, file => {
            const isImage = file.sourceImage?.metadata?.mime.startsWith('image/') || false

            return <div>
                {isImage && (
                    <img
                        src={(content as ContentTypeFileComparisonResult).sourceImage?.metadata.url}
                        alt={(content as ContentTypeFileComparisonResult).sourceImage?.metadata.name || 'Image'}
                        style={{maxWidth: '100%', maxHeight: '150px'}}
                    />
                )}
                <div className="mt-1">
                    <small>{(content as ContentTypeFileComparisonResult).sourceImage?.metadata.name}</small>
                </div>
            </div>

        })
    }

    const renderContent = () => {
        return ifContentTypeComparisonResult(content, content => {
            const strapiC = content.sourceContent || content.targetContent
            if (strapiC) {
                return <div>
                    {getRepresentativeAttributes(strapiC).map((attr, attrIndex) => (
                        <div key={attrIndex} className="mb-1">
                            <span className="font-bold">{attr.key}: </span>
                            <span>{attr.value}</span>
                        </div>
                    ))}
                </div>
            } else {
                return <div className="mb-1">
                    <span className="font-bold">{content.contentType}: </span>
                    <span> id: {content.id}</span>
                </div>
            }
        })
    }

    const isResolved = isComparisonItemResolved(allMergeData, content)


    return (
        <div key={index} className="col-12 md:col-6 lg:col-4 mb-2">
            <div
                className={`p-card p-3 ${isResolved ? 'bg-green-50' : 'bg-yellow-50'}`}
                style={identical ? { border: '2px solid #22c55e' } : undefined}
            >
                <div className="flex align-items-center">
                    <i className={`pi ${isResolved ? 'pi-check-circle text-green-500' : 'pi-exclamation-circle text-yellow-500'} mr-2`}></i>
                    <span className="font-bold">{title}</span>
                </div>
                <div className="mt-2">
                    <small className="text-500">
                        Related via: {link.field}
                    </small>
                </div>
                <div className="mt-2">
                    {renderFile()}
                    {renderContent()}
                </div>
                <div className="mt-2">
                    <small className={identical ? 'text-green-500' : (isResolved ? 'text-green-500' : 'text-yellow-500')}>
                        {identical ? 'Already synchronized' : (isResolved ? 'Resolved' : 'Not resolved - must be selected')}
                    </small>
                </div>
            </div>
        </div>
    );
};

export default RelationshipCard;