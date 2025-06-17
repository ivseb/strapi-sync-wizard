import React from 'react';
import { EntryRelationship } from '../../types';

interface RelationshipCardProps {
    relationship: EntryRelationship;
    index: number;
    targetEntry: any;
    isResolved: boolean;
    getRepresentativeAttributes: (entry: any) => { key: string, value: string }[];
}

/**
 * A reusable component for displaying relationship cards
 * Used in both MergeCollectionsStep and MergeSingleTypesStep
 */
const RelationshipCard: React.FC<RelationshipCardProps> = ({
    relationship,
    index,
    targetEntry,
    isResolved,
    getRepresentativeAttributes
}) => {
    // Check if it's an image
    const isImage = targetEntry.mime && targetEntry.mime.startsWith('image/');

    return (
        <div key={index} className="col-12 md:col-6 lg:col-4 mb-2">
            <div className={`p-card p-3 ${isResolved ? 'bg-green-50' : 'bg-yellow-50'}`}>
                <div className="flex align-items-center">
                    <i className={`pi ${isResolved ? 'pi-check-circle text-green-500' : 'pi-exclamation-circle text-yellow-500'} mr-2`}></i>
                    <span className="font-bold">{relationship.targetContentType}</span>
                </div>
                <div className="mt-2">
                    <small className="text-500">
                        Related via: {relationship.sourceField}
                    </small>
                </div>
                <div className="mt-2">
                    {isImage ? (
                        <div>
                            <img
                                src={targetEntry.url}
                                alt={targetEntry.name || 'Image'}
                                style={{maxWidth: '100%', maxHeight: '150px'}}
                            />
                            <div className="mt-1">
                                <small>{targetEntry.name}</small>
                            </div>
                        </div>
                    ) : (
                        <div>
                            {getRepresentativeAttributes(targetEntry).map((attr, attrIndex) => (
                                <div key={attrIndex} className="mb-1">
                                    <span className="font-bold">{attr.key}: </span>
                                    <span>{attr.value}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
                <div className="mt-2">
                    <small className={isResolved ? 'text-green-500' : 'text-yellow-500'}>
                        {isResolved ? 'Resolved' : 'Not resolved - must be selected'}
                    </small>
                </div>
            </div>
        </div>
    );
};

export default RelationshipCard;