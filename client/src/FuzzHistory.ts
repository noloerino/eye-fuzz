/**
 * The history of a fuzzing session is represented as a List<List<EiDiff>>.
 * Each item in the list represents a sequence of changes produced by the server.
 */
import {ByteTypeInfo, ExecutionIndex, EiIndex, StackTraceLine} from "./common"

type StackTrace = StackTraceLine[];

// deserialized version
export type FuzzHistory = {
    typeInfo: ByteTypeInfo[];
    eiList: number[][];
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
            stackTrace: StackTrace,
            "new": number,
        }[],
    }[];
};

type CompressedEiKey = {
    ei: number[], // Comes as array rather than string
    stackTrace: StackTrace,
};

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
        eiList: history.eiList.map(key => key.ei),
        runResults: history.runResults.map((rr) => {
            return {
                serializedResult: rr.serializedResult,
                markedUsed: rr.markedUsed,
                updateChoices: rr.updateChoices,
                createChoices: rr.createChoices.map(choice => {
                    return {
                        eiIndex: choice.eiIndex,
                        stackTrace: history.eiList[choice.eiIndex].stackTrace,
                        "new": choice.new,
                    };
                }),
            };
        })
    };
}
