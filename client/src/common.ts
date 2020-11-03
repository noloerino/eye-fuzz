
export type StackTraceLine = {
    callLocation: {
        iid: number;
        containingClass: string;
        containingMethodName: string;
        lineNumber: number;
        invokedMethodName: string;
    };
    count: number;
};

export type ExecutionIndex = string;

export type EiWithData = {
    ei: ExecutionIndex;
    eiHash: string;
    choice: number;
    stackTrace: StackTraceLine[];
    used: boolean;
};
