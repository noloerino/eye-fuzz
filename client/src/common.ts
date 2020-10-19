
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

export enum ChoiceKind {
    BOOLEAN = "BOOLEAN",
    BYTE = "BYTE",
    BYTE_ARRAY = "BYTE_ARRAY",
    CHAR = "CHAR",
    CHOOSE = "CHOOSE",
    DOUBLE = "DOUBLE",
    FLOAT = "FLOAT",
    INT = "INT",
    LONG = "LONG",
    SHORT = "SHORT"
}

export type ByteTypeInfo = {
    kind: ChoiceKind;
    byteOffset: number;
};

export type EiWithData = {
    ei: ExecutionIndex;
    eiHash: string;
    choice: number;
    typeInfo: ByteTypeInfo;
    stackTrace: StackTraceLine[];
    used: boolean;
};
