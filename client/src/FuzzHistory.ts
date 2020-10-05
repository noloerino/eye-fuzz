/**
 * The history of a fuzzing session is represented as a List<List<EiDiff>>.
 * Each item in the list represents a sequence of changes produced by the server.
 */
import {ExecutionIndex, StackTraceLine} from "./common"

type StackTrace = StackTraceLine[];

export default class FuzzHistory {
    runResults: {
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
}
