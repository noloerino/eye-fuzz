import m, {Vnode} from "mithril";
import { MithrilTsxComponent } from 'mithril-tsx-component';

const SERVER_URL = "http://localhost:8000";

interface ExecutionIndexDisplayAttrs {
    eiTableData: EiWithData[]
}

interface EiWithData {
    ei: string;
    choice: number;
    stackTrace: string;
}

class ExecutionIndexDisplay extends MithrilTsxComponent<ExecutionIndexDisplayAttrs> {
    view(vnode: Vnode<ExecutionIndexDisplayAttrs, this>) {
        return (
            <form id="rerunGenForm" method="POST" action="http://localhost:8000/generator">
                <table>
                    <thead>
                    <tr>
                        <th scope="col">ExecutionIndex</th>
                        <th scope="col">Last Event</th>
                        <th scope="col">Value</th>
                    </tr>
                    </thead>
                    <tbody id="eiTableBody">
                    {vnode.attrs.eiTableData.map(({ei, stackTrace, choice}, i) => (
                        <tr>
                            <td className="eiCell">
                                {ei}
                            </td>
                            <td>
                                {stackTrace}
                            </td>
                            <td>
                                <input type="number" min={0} max={255} value={choice} data-index={i}
                                    oninput={(e: InputEvent) => {
                                        // @ts-ignore
                                        let v = parseInt(e.target.value);
                                        // Handle NaN on POST side to let the field be empty
                                        // TODO ceiling/floor this
                                        vnode.attrs.eiTableData[i].choice = v;
                                    }}
                                />
                            </td>
                        </tr>
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
                    <td id="genContentCell">{vnode.attrs.genOutput}</td>
                </tr>
                </tbody>
            </table>
        );
    }
}

export class RootTable extends MithrilTsxComponent<{ }> {
    eiTableData: EiWithData[] = []
    genOutput: string = "";

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
                for (let {ei, stackTrace, choice} of arr) {
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
                        choice: choice,
                        stackTrace: filteredStackTrace.join("\n")
                    });
                }
            })
            .catch(e => {
                this.eiTableData = [{ei: "ERROR " + e.message, choice: 0, stackTrace: ""}]
            });
    }

    // Returns a promise to allow us to await on the result on this request
    postEi(): Promise<void> {
        let arr = this.eiTableData.map(({ei, choice}) => ({
            ei: JSON.parse(
                "[" + (
                    ei.replace(/[\(\)]/g, "")
                        .replace(/\n/g, " ")
                        .trim()
                        .replace(/ /g, ",")
                ) + "]"),
            choice // TODO fail NaN on choice (e.g. empty string)
        }));
        return m.request({
            method: "POST",
            url: SERVER_URL + "/ei",
            body: arr,
        });
    }


    getGenOutput() {
        // Mithril type defs don't yet have responseType, since otherwise it'll just try and fail to read JSON
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
            <table>
                <tbody>
                <tr>
                    <td>
                        <button type="submit" onclick={() => {
                            this.postEi().then(() => this.postGenOutput());
                        }}>
                            Rerun generator
                        </button>
                        <ExecutionIndexDisplay eiTableData={this.eiTableData}/>
                    </td>
                    <td>
                        <GenOutputDisplay genOutput={this.genOutput}/>
                    </td>
                </tr>
                </tbody>
            </table>
        );
    }
}