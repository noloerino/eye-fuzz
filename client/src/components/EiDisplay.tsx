import m, {Vnode} from "mithril";
// Necessary to prevent mithril from getting dead code eliminated...
const _m = m;
import {Bounds, EiIndex, EiWithData, getByte, setByte, StackTraceLine, TypedEiWithData} from "../common";
import {FuzzHistory} from "../FuzzHistory";
import {MithrilTsxComponent} from "mithril-tsx-component";

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
                        return (l.callLocation.containingClass.indexOf(targetClass) >= 0)
                            || (l.callLocation.invokedMethodName.indexOf(targetClass) >= 0)
                    })
                    .map(serializeStackTraceLine).join("\n")}
            </td>
        )
    }
}

type ByteDisplayAttrs = {
    eiTableData: EiWithData[];
    history: FuzzHistory;
    historyDepth: number;
    historicChoices: Map<EiIndex, number | null>;
    newEiChoices: Map<EiIndex, number>;
    classNameFilter: string;
    showUnused: boolean;
    renderer: (n: number | null) => string;
};

function serializeStackTraceLine(l: StackTraceLine): string {
    let cl = l.callLocation;
    return `(${l.count}) ${cl.containingClass}#${cl.containingMethodName}()@${cl.lineNumber} --> ${cl.invokedMethodName}`;
}

function displayEi(ei: number[]): string {
    let eiString = "";
    for (let i = 0; i < ei.length; i += 2) {
        eiString += ei[i] + " (" + ei[i + 1] + ")\n"
    }
    return eiString;
}

export class EiByteDisplay extends MithrilTsxComponent<ByteDisplayAttrs> {
    view(vnode: Vnode<ByteDisplayAttrs, this>) {
        return (
            <table>
                <thead>
                <tr>
                    <th scope="col">ExecutionIndex</th>
                    <th scope="col">Used</th>
                    <th scope="col">Stack Trace</th>
                    <th scope="col">Type Info</th>
                    <th scope="col">Value {vnode.attrs.historyDepth} Run(s) Ago</th>
                    <th scope="col">Current Value</th>
                    <th scope="col">New Value</th>
                </tr>
                </thead>
                <tbody id="eiTableBody">
                {vnode.attrs.eiTableData.flatMap((
                    {
                        ei,
                        stackTrace,
                        choice,
                        used
                    },
                    i
                ) => (
                    (vnode.attrs.showUnused || used) ? [(
                        <tr>
                            <td className="eiCell" style={{
                                maxWidth: "10em",
                                overflow: "scroll",
                                textOverflow: "clip",
                                whiteSpace: "pre-wrap"
                            }}>
                                {displayEi(ei)}
                            </td>
                            <td style={{textAlign: "center"}}>
                                <input type="checkbox" disabled={true} checked={used} />
                            </td>
                            <StackTraceCell classNameFilter={vnode.attrs.classNameFilter}
                                            stackTrace={stackTrace} />
                            <td>
                                {JSON.stringify(vnode.attrs.history.typeInfo[i])}
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

function computeOldChoice(currChoice: number, descendantIndices: EiIndex[], historicChoices: Map<EiIndex, number | null>): number {
    let oldChoice = currChoice;
    descendantIndices.map((eiIndex, ofs) => {
        let b = historicChoices.get(eiIndex) ?? getByte(currChoice, ofs);
        oldChoice = setByte(oldChoice, ofs, b);
    });
    return oldChoice;
}

type TypedDisplayAttrs = {
    typedData: TypedEiWithData[];
    eiList: number[][];
    history: FuzzHistory;
    historyDepth: number;
    historicChoices: Map<EiIndex, number | null>;
    newEiChoices: Map<EiIndex, number>;
    classNameFilter: string;
    showUnused: boolean;
    renderer: (n: number | null, bounds?: Bounds) => string;
};

export class EiTypedDisplay extends MithrilTsxComponent<TypedDisplayAttrs> {
    // Keeps track of choices made on types for convenience; should get converted to byte-level choices later
    private newTypedChoices: Map<number, number> = new Map();

    view(vnode: Vnode<TypedDisplayAttrs, this>) {
        let eiList = vnode.attrs.eiList;
        let historicChoices = vnode.attrs.historicChoices;
        let renderer = vnode.attrs.renderer;
        return (
            <table>
                <thead>
                <tr>
                    <th scope="col">ExecutionIndex</th>
                    <th scope="col">Used</th>
                    <th scope="col">Stack Trace</th>
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
                            <td className="eiCell" style={{
                                maxWidth: "10em",
                                overflow: "scroll",
                                textOverflow: "clip",
                                whiteSpace: "pre-wrap"
                            }}>
                                {displayEi(eiList[descendantIndices[0]])}
                            </td>
                            <td style={{textAlign: "center"}}>
                                <input type="checkbox" disabled={true} checked={used} />
                            </td>
                            <StackTraceCell classNameFilter={vnode.attrs.classNameFilter}
                                            stackTrace={stackTrace} />
                            <td style={{textAlign: "center"}}>
                                {kind}
                                {/* IMPORTANT: do not use the bounds argument of the renderer since these ARE the bounds */}
                                {intBounds && ` [${renderer(intBounds.min)}, ${renderer(intBounds.max)})`}
                            </td>
                            <td id="lessRecent" style={{textAlign: "center"}}>
                                <span>{
                                    // If the key is absent, then the value is the same as the current choice; if it
                                    // is present but null then it didn't yet exist
                                    renderer(
                                        descendantIndices.some((eiIndex) => historicChoices.has(eiIndex))
                                        ? computeOldChoice(choice, descendantIndices, historicChoices)
                                        : choice,
                                        intBounds ?? undefined
                                    )
                                }</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <span>{renderer(choice, intBounds ?? undefined)}</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <input type="number" min={intBounds?.min} max={intBounds?.max} value={
                                    this.newTypedChoices.get(i)
                                        ? (this.newTypedChoices.get(i)!! - (intBounds ? intBounds.min : 0))
                                            : ""}
                                        oninput={(e: InputEvent) => {
                                            let value = (e.target as HTMLInputElement)?.value ?? "";
                                            if (value === "") {
                                                this.newTypedChoices.delete(i);
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
                                                    console.log(`getting ${v}, which will become ${v + intBounds.min}`)
                                                    v += intBounds.min;
                                                }
                                                this.newTypedChoices.set(i, v);
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