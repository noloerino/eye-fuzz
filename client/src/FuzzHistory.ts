/**
 * The history of a fuzzing session is represented as a List<List<EiDiff>>.
 * Each item in the list represents a sequence of changes produced by the server.
 */
import {ExecutionIndex, StackTraceLine} from "./common"

type StackTrace = StackTraceLine[];

// types in tagged union should match FuzzSession.kt
type MarkUsed = {
    type: "EiDiff.MarkUsed";
    ei: ExecutionIndex;
};

type UpdateChoice = {
    type: "EiDiff.UpdateChoice";
    ei: ExecutionIndex;
    "new": number;
};

type Create = {
    type: "EiDiff.Create";
    ei: ExecutionIndex;
    stackTrace: StackTrace;
    choice: number;
};

type EiDiff = MarkUsed | UpdateChoice | Create;

class FuzzHistory {
    diffs: EiDiff[][];
}
