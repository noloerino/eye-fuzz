
export enum ByteRender {
    DECIMAL = "Decimal",
    BINARY = "Binary",
    HEX = "Hexadecimal"
}

export function renderNumber(n: number, format: ByteRender): string {
    switch (format) {
        case ByteRender.DECIMAL:
            return n.toString();
        case ByteRender.BINARY:
            return "0b" + n.toString(2);
        case ByteRender.HEX:
            return "0x" + n.toString(16);
        default:
            throw new Error("Bad number format: " + format);
    }
}

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

export type EiIndex = number;

export type EiWithData = {
    ei: number[];
    choice: number;
    stackTrace: StackTraceLine[];
    used: boolean;
};

export type TypedEiWithData = {
    descendantIndices: EiIndex[]; // indices of EI that are its children, in byte offset order
    kind: ChoiceKind;
    // TODO add bounds and handle byte array case
    choice: number;
    stackTrace: StackTraceLine[];
    used: boolean;
};

export function getByte(n: number, ofs: number): number {
    return (n >> (8 * ofs)) & 0xFF;
}

export function setByte(n: number, ofs: number, v: number): number {
    return n & (~(0xFF << (8 * ofs))) | (v << (8 * ofs));
}

export function addTypeInfo(allTypeInfo: ByteTypeInfo[], eis: EiWithData[]): TypedEiWithData[] {
    if (eis.length === 0) {
        return [];
    }
    console.assert(allTypeInfo.length > 0);
    console.assert(allTypeInfo[0].byteOffset == 0);
    let arr: TypedEiWithData[] = [];
    let curr: TypedEiWithData | null = null;
    allTypeInfo.forEach((typeInfo, i) => {
        if (typeInfo.byteOffset === 0) {
            if (curr != null) {
                arr.push(curr)
            }
            curr = {
                descendantIndices: [],
                kind: typeInfo.kind,
                choice: eis[i].choice,
                stackTrace: eis[i].stackTrace,
                used: eis[i].used
            };
        }
        curr!!.descendantIndices.push(i)
        // fine to |= because we assume those bytes haven't been filled yet
        curr!!.choice |= (eis[i].choice << (8 * typeInfo.byteOffset));
    });
    return arr;
}