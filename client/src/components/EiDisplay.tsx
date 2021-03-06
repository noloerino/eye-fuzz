import m, {Vnode} from "mithril";
import {
    Bounds,
    ChoiceKind,
    FuzzHistory,
    getByte,
    LocIndex,
    LocWithData,
    setByte,
    StackTraceInfo,
    StackTraceLine,
    TypedLocWithData,
} from "../common";
import {TOOLTIP_NEW_VALUE, TOOLTIP_STACK_TRACE, TOOLTIP_TYPE_INFO, TOOLTIP_USED} from "../tooltips";
import {MithrilTsxComponent} from "mithril-tsx-component";
// Necessary to prevent mithril from getting dead code eliminated...
const _m = m;

type StackTraceCellAttrs = {
    classNameFilter: string,
    stackTrace: StackTraceLine[]
};

class StackTraceCell extends MithrilTsxComponent<StackTraceCellAttrs> {
    view(vnode: Vnode<StackTraceCellAttrs, this>) {
        return (
            <td className="stackTraceCell" style={{
                maxWidth: "60em",
                overflow: "scroll",
                textOverflow: "clip",
                whiteSpace: "pre-wrap"
            }}>
                {vnode.attrs.stackTrace
                    .filter((l: StackTraceLine) => {
                        let targetClass = vnode.attrs.classNameFilter;
                        return (l.className.indexOf(targetClass) >= 0)
                    })
                    .map(serializeStackTraceLine).join("\n")}
            </td>
        )
    }
}

type ByteDisplayAttrs = {
    eiTableData: LocWithData[];
    history: FuzzHistory;
    historyDepth: number;
    historicChoices: Map<LocIndex, number | null>;
    newEiChoices: Map<LocIndex, number>;
    classNameFilter: string;
    showUnused: boolean;
    renderer: (n: number | null) => string;
};

function serializeStackTraceLine(l: StackTraceLine): string {
    return `${l.className}#${l.methodName}()@${l.lineNumber}`;
}

function renderKind(kind: ChoiceKind): string {
    switch (kind) {
        case ChoiceKind.BYTE_ARRAY:
            return "byte array";
        case ChoiceKind.CHOOSE:
            return "array index";
        default:
            return kind.toLowerCase();
    }
}

export class EiByteDisplay extends MithrilTsxComponent<ByteDisplayAttrs> {
    view(vnode: Vnode<ByteDisplayAttrs, this>) {
        return (
            <table>
                <thead>
                <tr>
                    <th scope="col" title={TOOLTIP_STACK_TRACE}>Stack Trace</th>
                    <th scope="col" title={TOOLTIP_USED}>Used</th>
                    <th scope="col" title={TOOLTIP_TYPE_INFO}>Type Info</th>
                    <th scope="col">Value {vnode.attrs.historyDepth} Run(s) Ago</th>
                    <th scope="col">Current Value</th>
                    <th scope="col" title={TOOLTIP_NEW_VALUE}>New Value</th>
                </tr>
                </thead>
                <tbody id="eiTableBody">
                {vnode.attrs.eiTableData.flatMap((
                    {stackTraceInfo: {stackTrace, typeInfo}, choice, used},
                    i
                ) => (
                    (vnode.attrs.showUnused || used) ? [(
                        <tr>
                            <StackTraceCell classNameFilter={vnode.attrs.classNameFilter}
                                            stackTrace={stackTrace} />
                            <td style={{textAlign: "center"}}>
                                <input type="checkbox" disabled={true} checked={used} />
                            </td>
                            <td>
                                <span title={"The type this call to random() produced"}>
                                    Data type: {renderKind(typeInfo.kind)}
                                </span>
                                <br />
                                <span title={"The offset within the type that this byte accounted for"}>
                                    Byte offset: {typeInfo.byteOffset}
                                </span>
                                {typeInfo.intBounds &&
                                <>
                                <br />
                                <span title={"The inclusive minimum and exclusive maximum value for this call to random()"}>
                                    Bounds:
                                    [{vnode.attrs.renderer(typeInfo.intBounds.min)}, {vnode.attrs.renderer(typeInfo.intBounds.max)})
                                </span>
                                </>
                                }
                            </td>
                            <td id="lessRecent" style={{textAlign: "center"}}>
                                <span>{
                                    // If the key is absent, then the value is the same as the current choice; if it
                                    // is present but null then it didn't yet exist
                                    vnode.attrs.renderer(vnode.attrs.historicChoices.has(i)
                                        ? (vnode.attrs.historicChoices.get(i) ?? null)
                                        : choice)
                                }</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <span>{vnode.attrs.renderer(choice)}</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <input type="number" min={0} max={255} value={vnode.attrs.newEiChoices.get(i) ?? ""}
                                       oninput={(e: InputEvent) => {
                                           let value = (e.target as HTMLInputElement)?.value ?? "";
                                           if (value === "") {
                                               vnode.attrs.newEiChoices.delete(i);
                                           } else {
                                               // TODO ceiling/floor this
                                               vnode.attrs.newEiChoices.set(i, parseInt(value));
                                           }
                                       }}
                                />
                            </td>
                        </tr>
                    )] : []
                ))}
                </tbody>
            </table>
        )
    }
}

function computeOldChoice(currChoice: number, descendantIndices: LocIndex[], historicChoices: Map<LocIndex, number | null>): number {
    let oldChoice = currChoice;
    descendantIndices.map((eiIndex, ofs) => {
        let b = historicChoices.get(eiIndex) ?? getByte(currChoice, ofs);
        oldChoice = setByte(oldChoice, ofs, b);
    });
    return oldChoice;
}

type TypedDisplayAttrs = {
    typedData: TypedLocWithData[];
    locList: StackTraceInfo[];
    history: FuzzHistory;
    historyDepth: number;
    historicChoices: Map<LocIndex, number | null>;
    // Keeps track of choices made on types for convenience; should get converted to byte-level choices later
    newTypedChoices: Map<number, number>;
    newEiChoices: Map<LocIndex, number>;
    classNameFilter: string;
    showUnused: boolean;
    renderer: (n: number | null, bounds?: Bounds) => string;
};

export class EiTypedDisplay extends MithrilTsxComponent<TypedDisplayAttrs> {
    view(vnode: Vnode<TypedDisplayAttrs, this>) {
        let historicChoices = vnode.attrs.historicChoices;
        let renderer = vnode.attrs.renderer;
        let typedChoices = vnode.attrs.newTypedChoices;
        // Workaround for inability to declare variables in JSX
        let _oldValue = 0;
        return (
            <table>
                <thead>
                <tr>
                    <th scope="col">Stack Trace</th>
                    <th scope="col">Used</th>
                    <th scope="col">Type</th>
                    <th scope="col">Value {vnode.attrs.historyDepth} Run(s) Ago</th>
                    <th scope="col">Current Value</th>
                    <th scope="col">New Value</th>
                </tr>
                </thead>
                <tbody id="eiTableBody">
                {vnode.attrs.typedData.flatMap((
                    {
                        descendantIndices,
                        kind,
                        intBounds,
                        choice,
                        stackTrace,
                        used
                    },
                    i
                ) => (
                    (vnode.attrs.showUnused || used) ? [(
                        <tr>
                            <StackTraceCell classNameFilter={vnode.attrs.classNameFilter}
                                            stackTrace={stackTrace} />
                            <td style={{textAlign: "center"}}>
                                <input type="checkbox" disabled={true} checked={used} />
                            </td>
                            <td style={{textAlign: "center"}}>
                                {renderKind(kind)}
                                {/* IMPORTANT: do not use the bounds argument of the renderer since these ARE the bounds */}
                                {intBounds && ` [${renderer(intBounds.min)}, ${renderer(intBounds.max)})`}
                            </td>
                            <td id="lessRecent" style={{textAlign: "center"}}>
                                <span>{
                                    // If the key is absent, then the value is the same as the current choice; if it
                                    // is present but null then it didn't yet exist
                                    (_oldValue = descendantIndices.some((eiIndex) => historicChoices.has(eiIndex))
                                        ? computeOldChoice(choice, descendantIndices, historicChoices)
                                        : choice)
                                    && (kind === ChoiceKind.BOOLEAN)
                                        ? (_oldValue % 2 ? "true" : "false")
                                        : renderer(_oldValue, intBounds ?? undefined)
                                }</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <span>{
                                    kind === ChoiceKind.BOOLEAN
                                        ? (choice % 2 ? "true" : "false")
                                        : renderer(choice, intBounds ?? undefined)
                                }</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <input type="number" min={intBounds?.min} max={intBounds?.max} value={
                                    typedChoices.get(i) || typedChoices.get(i) === 0
                                            ? (typedChoices.get(i)!! - (intBounds ? intBounds.min : 0))
                                            : ""}
                                        oninput={(e: InputEvent) => {
                                            let value = (e.target as HTMLInputElement)?.value ?? "";
                                            if (value === "") {
                                                typedChoices.delete(i);
                                                vnode.attrs.typedData[i].descendantIndices.forEach((eiIndex) => {
                                                    vnode.attrs.newEiChoices.delete(eiIndex);
                                                });
                                            } else {
                                                let v = parseInt(value);
                                                if (isNaN(v)) {
                                                    // Treat it as unicode
                                                    // type="number" probably prevents this though :(
                                                    v = value.charCodeAt(0);
                                                }
                                                if (intBounds)  {
                                                    // Offset by bounds - just add to min
                                                    // console.log(`getting ${v}, which will become ${v + intBounds.min}`)
                                                    v += intBounds.min;
                                                }
                                                typedChoices.set(i, v);
                                                vnode.attrs.typedData[i].descendantIndices.forEach((eiIndex, ofs) => {
                                                    vnode.attrs.newEiChoices.set(eiIndex, getByte(v, ofs));
                                                });
                                            }
                                        }}
                                />
                            </td>
                        </tr>
                    )] : []
                ))}
                </tbody>
            </table>
        );
    }
}