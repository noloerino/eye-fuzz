/**
 * The history of a fuzzing session is represented as a List<List<EiDiff>>.
 * Each item in the list represents a sequence of changes produced by the server.
 */
import {ExecutionIndex, StackTraceLine} from "./common"

type StackTrace = StackTraceLine[];

// deserialized version
export type FuzzHistory = {
    runResults: {
        serializedResult: string;
        markUsed: ExecutionIndex[],
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
    ei: ExecutionIndex,
    stackTrace: StackTrace,
};

export type EiIndex = number;

export class SerializedFuzzHistory {
    eiList: CompressedEiKey[];
    runResults: {
        serializedResult: string;
        markUsed: EiIndex[],
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

    toFuzzHistory(): FuzzHistory {
        return {
            runResults: this.runResults.map(rr => {
                return {
                    serializedResult: rr.serializedResult,
                    markUsed: rr.markUsed.map(i => this.eiList[i].ei),
                    updateChoices: rr.updateChoices.map(choice => {
                        return {
                            ei: this.eiList[choice.eiIndex].ei,
                            old: choice.old,
                            "new": choice.new,
                        };
                    }),
                    createChoices: rr.createChoices.map(choice => {
                        return {
                            ei: this.eiList[choice.eiIndex].ei,
                            stackTrace: this.eiList[choice.eiIndex].stackTrace,
                            "new": choice.new,
                        };
                    }),
                };
            })
        };
    }
}
