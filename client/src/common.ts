
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

export type EiWithData = {
    ei: ExecutionIndex;
    choice: number;
    stackTrace: StackTraceLine[];
    used: boolean;
};

export type TypedEiWithData = {
    descendantIndices: number[]; // indices of EI that are its children, in byte offset order
    kind: ChoiceKind;
    // TODO add bounds and handle byte array case
    choice: number;
    stackTrace: StackTraceLine[];
    used: boolean;
};

export function addTypeInfo(allTypeInfo: ByteTypeInfo[], eis: EiWithData[]): TypedEiWithData[] {
    console.assert(allTypeInfo.length > 0);
    console.assert(allTypeInfo[0].byteOffset == 0);
    let arr = [];
    let curr: TypedEiWithData | null = null;
    for (let i in allTypeInfo) {
        let typeInfo = allTypeInfo[i];
        if (typeInfo.byteOffset == 0) {
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
        console.assert(typeInfo.byteOffset === arr.length);
        curr!!.descendantIndices.push()
        curr!!.choice |= (eis[i].choice << (8 * typeInfo.byteOffset));
    }
    return arr;
}

export function removeTypeInfo(typedEis: TypedEiWithData[], eiList: ExecutionIndex[]): EiWithData[] {
    let arr: EiWithData[] = [];
    for (let typedEi of typedEis) {
        typedEi.descendantIndices.forEach((child, i) =>{
            arr.push(
                {
                    ei: eiList[child],
                    // Select appropriate byte
                    choice: (typedEi.choice >> (8 * i)) & 0xFF,
                    stackTrace: typedEi.stackTrace,
                    used: typedEi.used
                }
            );
        });
    }
    return arr;
}