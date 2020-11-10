/**
 * The history of a fuzzing session is represented as a List<List<EiDiff>>.
 * Each item in the list represents a sequence of changes produced by the server.
 */
import {ByteTypeInfo, ExecutionIndex, StackTraceLine} from "./common"

type StackTrace = StackTraceLine[];

// deserialized version
export type FuzzHistory = {
    typeInfo: ByteTypeInfo[];
    runResults: {
        serializedResult: string;
        markedUsed: ExecutionIndex[],
        updateChoices: {
            ei: ExecutionIndex,
            old: number,
            "new": number,
        }[],
        createChoices: {
            ei: ExecutionIndex,
            stackTrace: StackTrace,
            "new": number,
        }[],
    }[];
};

type CompressedEiKey = {
    ei: number[], // Comes as array rather than string
    stackTrace: StackTrace,
};

export type EiIndex = number;

export type SerializedFuzzHistory = {
    allTypeInfo: ByteTypeInfo[];
    eiList: CompressedEiKey[];
    runResults: {
        serializedResult: string;
        markedUsed: EiIndex[],
        updateChoices: {
            eiIndex: EiIndex,
            old: number,
            "new": number,
        }[],
        createChoices: {
            eiIndex: EiIndex,
            "new": number,
        }[],
    }[];
}

export function deserializeFuzzHistory(history: SerializedFuzzHistory): FuzzHistory {
    return {
        typeInfo: history.allTypeInfo,
        runResults: history.runResults.map((rr) => {
            return {
                serializedResult: rr.serializedResult,
                markedUsed: rr.markedUsed.map(i => JSON.stringify(history.eiList[i].ei)),
                updateChoices: rr.updateChoices.map(choice => {
                    return {
                        ei: JSON.stringify(history.eiList[choice.eiIndex].ei),
                        old: choice.old,
                        "new": choice.new,
                    };
                }),
                createChoices: rr.createChoices.map(choice => {
                    return {
                        ei: JSON.stringify(history.eiList[choice.eiIndex].ei),
                        stackTrace: history.eiList[choice.eiIndex].stackTrace,
                        "new": choice.new,
                    };
                }),
            };
        })
    };
}
