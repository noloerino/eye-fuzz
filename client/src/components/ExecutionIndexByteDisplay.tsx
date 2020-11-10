import m, {Vnode} from "mithril";
// Necessary to prevent mithril from getting dead code eliminated...
const _m = m;
import {EiWithData, ExecutionIndex, StackTraceLine} from "../common";
import {FuzzHistory} from "../FuzzHistory";
import {MithrilTsxComponent} from "mithril-tsx-component";

type ExecutionIndexDisplayAttrs = {
    eiTableData: EiWithData[];
    history: FuzzHistory;
    historyDepth: number;
    historicChoices: Map<ExecutionIndex, number | null>;
    newEiChoices: Map<number, number>;
    classNameFilter: string;
    showUnused: boolean;
    renderer: (n: number | null) => string;
};

function serializeStackTraceLine(l: StackTraceLine): string {
    let cl = l.callLocation;
    return `(${l.count}) ${cl.containingClass}#${cl.containingMethodName}()@${cl.lineNumber} --> ${cl.invokedMethodName}`;
}

function displayEi(ei: ExecutionIndex): string {
    let eiString = "";
    for (let i = 0; i < ei.length; i += 2) {
        eiString += ei[i] + " (" + ei[i + 1] + ")\n"
    }
    return eiString;
}

export class ExecutionIndexByteDisplay extends MithrilTsxComponent<ExecutionIndexDisplayAttrs> {
    view(vnode: Vnode<ExecutionIndexDisplayAttrs, this>) {
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
                            <td className="stackTraceCell" style={{
                                maxWidth: "60em",
                                overflow: "scroll",
                                textOverflow: "clip",
                                whiteSpace: "pre-wrap"
                            }}>
                                {stackTrace
                                    .filter((l: StackTraceLine) => {
                                        let targetClass = vnode.attrs.classNameFilter;
                                        return (l.callLocation.containingClass.indexOf(targetClass) >= 0)
                                            || (l.callLocation.invokedMethodName.indexOf(targetClass) >= 0)
                                    })
                                    .map(serializeStackTraceLine).join("\n")}
                            </td>
                            <td>
                                {JSON.stringify(vnode.attrs.history.typeInfo[i])}
                            </td>
                            <td id="lessRecent" style={{textAlign: "center"}}>
                                <span>{
                                    // If the key is absent, then the value is the same as the current choice; if it
                                    // is present but null then it didn't yet exist
                                    vnode.attrs.renderer(vnode.attrs.historicChoices.has(JSON.stringify(ei))
                                        ? (vnode.attrs.historicChoices.get(JSON.stringify(ei)) ?? null)
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
