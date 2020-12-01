
export enum ByteRender {
    DECIMAL = "Decimal",
    BINARY = "Binary",
    HEX = "Hexadecimal",
    UTF16 = "UTF-16",
}

export function renderNumber(n: number, format: ByteRender, bounds?: Bounds): string {
    if (bounds) {
        // When bounds exist, if bounds are not the min and max of the type (TODO check that),
        // then n will be offset from the min, taken mod (max - min)
        let range = bounds.max - bounds.min;
        n %= range;
        if (n < 0) {
            n += range;
        }
        n += bounds.min;
    }
    switch (format) {
        case ByteRender.DECIMAL:
            return n.toString();
        case ByteRender.BINARY:
            return "0b" + n.toString(2);
        case ByteRender.HEX:
            // TODO deal with negatives
            return "0x" + n.toString(16);
        case ByteRender.UTF16:
            try {
                return "'" + String.fromCodePoint(n) + "'";
            } catch (e: any) {
                return "<invalid codepoint 0x" + n.toString(16) + ">";
            }
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

export type Bounds = {
    min: number,
    max: number
};

export type StackTrace = StackTraceLine[];

export type StackTraceInfo = {
    stackTrace: StackTrace,
    typeInfo: ByteTypeInfo
};

export type ByteTypeInfo = {
    kind: ChoiceKind;
    byteOffset: number;
    intBounds: Bounds | null;
};

export type StackTraceLine = {
    className: string;
    fileName: string;
    lineNumber: number;
    methodName: string;
};

export type LocIndex = number;

export type LocWithData = {
    stackTraceInfo: StackTraceInfo;
    choice: number;
    used: boolean;
};

export type TypedLocWithData = {
    descendantIndices: LocIndex[]; // indices of locations that are its children, in byte offset order
    kind: ChoiceKind;
    intBounds: Bounds | null;
    // TODO handle byte array case
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

export function addTypeInfo(locs: LocWithData[]): TypedLocWithData[] {
    if (locs.length === 0) {
        return [];
    }
    let arr: TypedLocWithData[] = [];
    let curr: TypedLocWithData | null = null;
    locs.forEach(({stackTraceInfo: {stackTrace, typeInfo}, choice, used}, i) => {
        if (typeInfo.byteOffset === 0) {
            if (curr != null) {
                arr.push(curr)
            }
            curr = {
                descendantIndices: [],
                kind: typeInfo.kind,
                intBounds: typeInfo.intBounds,
                choice,
                stackTrace,
                used,
            };
        }
        curr!!.descendantIndices.push(i)
        // fine to |= because we assume those bytes haven't been filled yet
        curr!!.choice |= (choice << (8 * typeInfo.byteOffset));
    });
    if (curr != null) {
        arr.push(curr);
    }
    return arr;
}

export type UpdateChoice = {
    locIndex: LocIndex,
    old: number,
    "new": number,
};

export type CreateChoice = {
    locIndex: LocIndex;
    "new": number;
};

// deserialized version
export type FuzzHistory = {
    locList: StackTraceInfo[];
    runResults: {
        serializedResult: string | null;
        markedUsed: LocIndex[],
        updateChoices: UpdateChoice[],
        createChoices: CreateChoice[],
    }[];
};
