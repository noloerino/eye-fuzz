import m, {Vnode} from "mithril";
import { MithrilTsxComponent } from 'mithril-tsx-component';

const SERVER_URL = "http://localhost:8000";

interface ExecutionIndexDisplayAttrs {
    eiTableData: EiWithData[];
    newEiChoices: Map<number, number>;
    showUnused: boolean;
}

interface EiWithData {
    ei: string;
    choice: number;
    stackTrace: string;
    used: boolean;
}

class ExecutionIndexDisplay extends MithrilTsxComponent<ExecutionIndexDisplayAttrs> {
    view(vnode: Vnode<ExecutionIndexDisplayAttrs, this>) {
        return (
            <form id="rerunGenForm" method="POST" action="http://localhost:8000/generator">
                <table>
                    <thead>
                    <tr>
                        <th scope="col">ExecutionIndex</th>
                        <th scope="col">Used</th>
                        <th scope="col">Stack Trace</th>
                        <th scope="col">Old Value</th>
                        <th scope="col">New Value</th>
                    </tr>
                    </thead>
                    <tbody id="eiTableBody">
                    {vnode.attrs.eiTableData.flatMap(({ei, stackTrace, choice, used}, i) => (
                        (vnode.attrs.showUnused || used) ? [(
                            <tr>
                                <td className="eiCell" style={{
                                    maxWidth: "20em",
                                    overflow: "scroll",
                                    textOverflow: "clip",
                                }}>
                                    {ei}
                                </td>
                                <td>
                                    <input type="checkbox" disabled={true} checked={used} />
                                </td>
                                <td>
                                    {stackTrace}
                                </td>
                                <td>
                                    <span>{choice}</span>
                                </td>
                                <td>
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
            </form>
        )
    }
}

interface GenOutputDisplayAttrs {
    genOutput: string
}

class GenOutputDisplay extends MithrilTsxComponent<GenOutputDisplayAttrs> {
    view(vnode: Vnode<GenOutputDisplayAttrs, this>) {
        return (
            <table>
                <thead>
                <tr>
                    {/* TODO show fields instead? */}
                    <th scope="col">Generator Serialized Output</th>
                </tr>
                </thead>
                <tbody id="generatorTableBody">
                <tr>
                    <td id="genContentCell" style={{
                        maxHeight: "20em",
                        maxWidth: "30em",
                        overflow: "scroll",
                        whiteSpace: "nowrap",
                        fontSize: 14,
                        fontFamily: '"PT Mono", "Courier"'
                    }}>
                        {vnode.attrs.genOutput}
                    </td>
                </tr>
                </tbody>
            </table>
        );
    }
}

export class RootTable extends MithrilTsxComponent<{ }> {
    eiTableData: EiWithData[] = [];
    newEiChoices: Map<number, number> = new Map();
    genOutput: string = "";
    showUnused: boolean = true;

    oninit() {
        this.getGenOutput();
        this.getEi();
    }

    getEi() {
        m.request({
            method: "GET",
            url: SERVER_URL + "/ei",
        })
            .then((arr: EiWithData[]) => {
                this.eiTableData = [];
                for (let {ei, stackTrace, choice, used} of arr) {
                    let stackLines = stackTrace.split("||");
                    let filteredStackTrace: string[] =
                        // stackLines;
                        stackLines.filter((l: string) => l.indexOf("JavaScriptCodeGenerator") > 0);
                    let eiString = "";
                    for (let i = 0; i < ei.length; i += 2) {
                        eiString += ei[i] + " (" + ei[i + 1] + ")\n"
                    }
                    this.eiTableData.push({
                        ei: eiString,
                        choice,
                        stackTrace: filteredStackTrace.join("\n"),
                        used,
                    });
                }
            })
            .catch(e => {
                this.eiTableData = [{ei: "ERROR " + e.message, choice: 0, stackTrace: "", used: false}]
            });
    }

    // Returns a promise to allow us to await on the result on this request
    updateEi(): Promise<void> {
        let arr = Array.from(this.newEiChoices.entries()).map(([i, choice]) => ({
            ei: JSON.parse(
                "[" + (
                    this.eiTableData[i].ei.replace(/[()]/g, "")
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


    getGenOutput() {
        // Mithril type defs don't yet have responseType, but excluding will cause it to just try and fail to read JSON
        // @ts-ignore
        m.request({
            method: "GET",
            url: SERVER_URL + "/generator",
            responseType: "text",
        })
            .then((responseText: any) => {
                this.genOutput = responseText
            })
            // .catch(e => this.genOutput = "ERROR " + e);
    }

    postGenOutput() {
        m.request({
            method: "POST",
            url: SERVER_URL + "/generator",
        })
            .then(() => this.getGenOutput())
            // .catch(e => this.genOutput = "ERROR " + e);
    }

    view() {
        return (
            <div>
                <button type="submit" onclick={() => {
                    this.updateEi().then(() => {
                        this.getEi();
                        this.postGenOutput();
                    });
                }}>
                    Rerun generator
                </button>
                <div>
                    <label>
                        <input type="checkbox" id="showUnused" checked={this.showUnused}
                               oninput={(e: Event) => {
                                   console.log("checked:", this.showUnused)
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

                            <ExecutionIndexDisplay eiTableData={this.eiTableData} newEiChoices={this.newEiChoices}
                                                   showUnused={this.showUnused}/>
                        </td>
                        <td>
                            <GenOutputDisplay genOutput={this.genOutput}/>
                        </td>
                    </tr>
                    </tbody>
                </table>
            </div>
        );
    }
}