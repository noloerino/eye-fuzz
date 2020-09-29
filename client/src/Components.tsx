import m, {Vnode} from "mithril";
import { MithrilTsxComponent } from 'mithril-tsx-component';

const SERVER_URL = "http://localhost:8000";

interface ExecutionIndexDisplayAttrs {
    eiTableData: EiWithData[];
    prevEiChoices: Map<ExecutionIndex, number>;
    newEiChoices: Map<number, number>;
    showUnused: boolean;
}

interface StackTraceLine {
    callLocation: {
        iid: number;
        containingClass: string;
        containingMethodName: string;
        lineNumber: number;
        invokedMethodName: string;
    };
    count: number;
}

function serializeStackTraceLine(l: StackTraceLine): string {
    let cl = l.callLocation;
    return `(${l.count}) ${cl.containingClass}#${cl.containingMethodName}()@${cl.lineNumber} --> ${cl.invokedMethodName}`;
}

type ExecutionIndex = string;

interface EiWithData {
    ei: ExecutionIndex;
    eiHash: string;
    choice: number;
    stackTrace: StackTraceLine[];
    used: boolean;
}

class ExecutionIndexDisplay extends MithrilTsxComponent<ExecutionIndexDisplayAttrs> {
    view(vnode: Vnode<ExecutionIndexDisplayAttrs, this>) {
        return (
            <table>
                <thead>
                <tr>
                    <th scope="col">ExecutionIndex</th>
                    <th scope="col">Hash</th>
                    <th scope="col">Used</th>
                    <th scope="col">Stack Trace</th>
                    <th scope="col">Previous Value</th>
                    <th scope="col">Current Value</th>
                    <th scope="col">New Value</th>
                </tr>
                </thead>
                <tbody id="eiTableBody">
                {vnode.attrs.eiTableData.flatMap(({ei, eiHash, stackTrace, choice, used}, i) => (
                    (vnode.attrs.showUnused || used) ? [(
                        <tr>
                            <td className="eiCell" style={{
                                maxWidth: "10em",
                                overflow: "scroll",
                                textOverflow: "clip",
                                whiteSpace: "pre-wrap"
                            }}>
                                {ei}
                            </td>
                            <td style={{textAlign: "center"}}>
                                {eiHash}
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
                                {stackTrace.map(serializeStackTraceLine).join("\n")}
                            </td>
                            <td style={{textAlign: "center"}}>
                                <span>{vnode.attrs.prevEiChoices.get(ei) ?? "--"}</span>
                            </td>
                            <td style={{textAlign: "center"}}>
                                <span>{choice}</span>
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

interface GenOutputDisplayAttrs {
    currOutput: string;
    prevOutput: string;
}

const GEN_CELL_STYLE = {
    maxHeight: "20em",
    maxWidth: "30em",
    overflow: "scroll",
    whiteSpace: "nowrap",
    fontSize: 14,
    fontFamily: '"PT Mono", "Courier"'
};

class GenOutputDisplay extends MithrilTsxComponent<GenOutputDisplayAttrs> {
    view(vnode: Vnode<GenOutputDisplayAttrs, this>) {
        return (
            <>
                <table>
                    <thead>
                    <tr>
                        <th scope="col">Current Generator Output</th>
                    </tr>
                    </thead>
                    <tbody>
                    <tr>
                        <td style={GEN_CELL_STYLE}>
                            {vnode.attrs.currOutput}
                        </td>
                    </tr>
                    </tbody>
                </table>
                <table>
                    <thead>
                    <tr>
                        <th scope="col">Previous Generator Output</th>
                    </tr>
                    </thead>
                    <tbody>
                    <tr>
                        <td style={GEN_CELL_STYLE}>
                            {vnode.attrs.prevOutput}
                        </td>
                    </tr>
                    </tbody>
                </table>
            </>
        );
    }
}

interface RunInfo {
    eiTableData: EiWithData[];
    genOutput: string;
}

interface OldRunInfo {
    oldEiChoices: Map<ExecutionIndex, number>;
    genOutput: string;
}

export class RootTable extends MithrilTsxComponent<{ }> {
    currRunInfo: RunInfo = {eiTableData: [], genOutput: ""};
    prevRunInfo: OldRunInfo = {oldEiChoices: new Map(), genOutput: ""};
    newEiChoices: Map<number, number> = new Map();
    showUnused: boolean = true;
    saveFileName: string | undefined;
    loadFileName: string | undefined;
    availableLoadFiles: string[] | undefined;

    oninit() {
        this.getEiAndGenOutput();
        this.getLoadFiles();
    }

    // Returns a promise to allow us to await on the result on this request
    updateEi(): Promise<void> {
        let arr = Array.from(this.newEiChoices.entries()).map(([i, choice]) => ({
            ei: JSON.parse(
                "[" + (
                    this.currRunInfo.eiTableData[i].ei.replace(/[()]/g, "")
                        .replace(/\n/g, " ")
                        .trim()
                        .replace(/ /g, ",")
                ) + "]"),
            choice
        }));
        this.newEiChoices.clear();
        return m.request({
            method: "PATCH",
            url: SERVER_URL + "/ei",
            body: arr,
        });
    }

    postGenOutput(): Promise<void> {
        return m.request({
            method: "POST",
            url: SERVER_URL + "/generator",
        });
            // .then(() => this.getGenOutput());
            // .catch(e => this.genOutput = "ERROR " + e);
    }

    /**
     * Performs requests to get the EI and generator output, and also updates the curr/prev run info objects.
     */
    getEiAndGenOutput() {
        Promise.all([
            // Get generator output
            // Mithril type defs don't yet have responseType, but excluding will cause it to just try and fail to read JSON
            // @ts-ignore
            m.request({
                method: "GET",
                url: SERVER_URL + "/generator",
                responseType: "text",
            })
                // Needed to type check for now
                .then((responseText: string) => responseText),
            // Get new EI
            m.request({
                method: "GET",
                url: SERVER_URL + "/ei",
            })
                .then((arr: EiWithData[]) =>
                    arr.map(({ei, eiHash, stackTrace, choice, used}) => {
                            const targetClass = "JavaScriptCodeGenerator";
                            let filteredStackTrace: StackTraceLine[] =
                                // stackTrace;
                                stackTrace.filter((l: StackTraceLine) =>
                                    (l.callLocation.containingClass.indexOf(targetClass) >= 0)
                                    || (l.callLocation.invokedMethodName.indexOf(targetClass) >= 0)
                                );
                            let eiString = "";
                            for (let i = 0; i < ei.length; i += 2) {
                                eiString += ei[i] + " (" + ei[i + 1] + ")\n"
                            }
                            return {
                                ei: eiString,
                                eiHash,
                                choice,
                                stackTrace: filteredStackTrace,
                                used,
                            };
                        }
                    ))
                .catch(e =>
                    [{ei: "ERROR " + e.message, eiHash: "", choice: 0, stackTrace: [], used: false}]
                )
        ])
            .then(([genOutput, eiData]) => {
                let oldEiChoices = new Map();
                for (let {ei, choice} of this.currRunInfo.eiTableData) {
                    oldEiChoices.set(ei, choice);
                }
                this.prevRunInfo = {
                    oldEiChoices,
                    genOutput: this.currRunInfo.genOutput,
                };
                this.currRunInfo = {
                    eiTableData: eiData,
                    genOutput,
                }
            });
    }

    getLoadFiles() {
        m.request({
            method: "GET",
            url: SERVER_URL + "/load_input",
        })
            .then((files: string[]) => {
                this.availableLoadFiles = files;
            });
    }

    view() {
        return (
            <div>
                <div id="controlPanel">
                    <button type="submit" onclick={() =>
                        this.updateEi()
                            .then(() => this.postGenOutput())
                            .then(() => this.getEiAndGenOutput())
                    }>
                        Rerun generator
                    </button>
                    <br />
                    <button type="submit" onclick={() =>{
                        m.request({
                            method: "POST",
                            url: SERVER_URL + "/reset",
                        })
                            .then(() => console.log("Cleared existing EI"))
                            .then(() => this.getEiAndGenOutput());
                    }}>
                        Restart from scratch
                    </button>
                    <form id="saveForm" method="POST" onsubmit={(e: Event) => {
                        let saveFileName = this.saveFileName;
                        e.preventDefault();
                        m.request({
                            method: "POST",
                            url: SERVER_URL + "/save_input",
                            body: {fileName: saveFileName},
                        }).then(() => console.log("Saved file", saveFileName, "(probably)"));
                        this.saveFileName = undefined;
                    }}>
                        <label>
                            Save last input to file:{" "}
                            <input type="text" value={this.saveFileName} required oninput={(e: InputEvent) =>
                                this.saveFileName = (e.target!! as HTMLInputElement).value
                            }/>
                        </label>
                        {/* TODO add feedback for save success/fail */}
                        <button type="submit">Save</button>
                    </form>
                    <form id="loadForm" method="POST" onsubmit={(e: Event) => {
                        let loadFileName = this.loadFileName;
                        e.preventDefault();
                        if (loadFileName) {
                            m.request({
                                method: "POST",
                                url: SERVER_URL + "/load_input",
                                body: {fileName: loadFileName},
                            })
                                .then(() => console.log("Loaded file", loadFileName))
                                .then(() => this.getEiAndGenOutput());
                            this.loadFileName = undefined;
                        }
                    }}>
                        <label>
                            Load input file:{" "}
                            <select value={this.loadFileName} onchange={(e: Event) => {
                                this.loadFileName = (e.target!! as HTMLSelectElement).value;
                            }}>
                                <option value=""/>
                                {
                                    this.availableLoadFiles?.map((fileName: string) =>
                                        <option value={fileName}>{fileName}</option>)
                                }
                            </select>
                        </label>
                        {/* TODO add feedback for save success/fail */}
                        <button type="submit">Load</button>
                    </form>
                    <label>
                        <input type="checkbox" id="showUnused" checked={this.showUnused}
                               oninput={(e: Event) => {
                                   this.showUnused = (e.target as HTMLInputElement).checked;
                               }}
                        />
                        Show unused EI
                    </label>
                </div>
                <table>
                    <tbody>
                    <tr>
                        <td>
                            <ExecutionIndexDisplay eiTableData={this.currRunInfo.eiTableData}
                                                   newEiChoices={this.newEiChoices}
                                                   prevEiChoices={this.prevRunInfo.oldEiChoices}
                                                   showUnused={this.showUnused}/>
                        </td>
                        <td>
                            <GenOutputDisplay currOutput={this.currRunInfo.genOutput}
                                              prevOutput={this.prevRunInfo.genOutput}/>
                        </td>
                    </tr>
                    </tbody>
                </table>
            </div>
        );
    }
}