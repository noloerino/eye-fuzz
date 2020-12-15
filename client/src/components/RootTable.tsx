import m from "mithril";
import { MithrilTsxComponent } from 'mithril-tsx-component';
import "../common";
import {ByteRender, renderNumber, ChoiceKind, LocWithData, LocIndex, addTypeInfo, FuzzHistory} from "../common";
import {EiByteDisplay, EiTypedDisplay} from "./EiDisplay";
import {GenOutputDisplay} from "./GenOutputDisplay";

const SERVER_URL = "http://localhost:8000";

const storage = window.localStorage;

/**
 * Returns a map of old choices for the specified depth.
 * If an EI didn't exist at a given point in history, its value will be null; if it existed but its value has not
 * been changed, then undefined is returned.
 * @param history
 * @param ago
 */
function getHistoricChoices(history: FuzzHistory, ago: number): Map<LocIndex, number | null> {
    let oldEiChoices = new Map();
    // Examine every update or create from current to (ago) creations in the past
    for (let i = 0; i < ago; i++) {
        // TODO because updates are stored BEFORE a generator run is reset, we actually need to
        // look one level deeper for updates
        let lastUpdates = history.runResults[history.runResults.length - ago - 1]?.updateChoices ?? [];
        lastUpdates.forEach(({locIndex, old}) => oldEiChoices.set(locIndex, old));
        let lastCreates = history.runResults[history.runResults.length - ago]?.createChoices ?? [];
        lastCreates.forEach(({locIndex}) => oldEiChoices.set(locIndex, null));
    }
    return oldEiChoices;
}

type RunInfo = {
    eiTableData: LocWithData[];
    genOutput: string;
};

enum TestStatus {
    SUCCESS = "SUCCESS",
    TIMEOUT = "TIMEOUT",
    FAILURE = "FAILURE",
    INVALID = "INVALID"
}

type TestCov = {
    status: TestStatus;
    cov: string;
};

enum ActiveTab {
    EI,
    COV
}

export class RootTable extends MithrilTsxComponent<{ }> {
    displayTypedEi: boolean = true;
    history: FuzzHistory = { locList: [], runResults: [] };
    currRunInfo: RunInfo = { eiTableData: [], genOutput: ""};
    newEiChoices: Map<LocIndex, number> = new Map();
    showUnused: boolean = true;
    byteRender: ByteRender = ByteRender.DECIMAL;
    saveFileName: string | undefined;
    loadFileName: string | undefined;
    saveSessionName: string | undefined;
    loadSessionName: string | undefined;
    availableLoadFiles: string[] | undefined;
    availableLoadSessions: string[] | undefined;
    testCov: TestCov = {status: TestStatus.INVALID, cov: ""};

    /**
     * The number of steps back in the history to display; 0 means the most recent set of values is displayed,
     * 1 means the previous set and the set before, etc..
     */
    historyDepth: number;

    genTabMouseOver = false;
    covTabMouseOver = false;

    resetHistoryDepth() {
        this.historyDepth = 1;
    }

    private _activeTab: ActiveTab = JSON.parse(storage.getItem("activeTab") ?? JSON.stringify(ActiveTab.EI));
    get activeTab(): ActiveTab {
        return this._activeTab;
    }
    set activeTab(value) {
        storage.setItem("activeTab", JSON.stringify(value));
        this._activeTab = value;
    }

    /**
     * A string by which to filter the class name of produced coverage, persisted in localStorage.
     */
    private _classNameFilter: string = storage.getItem("classNameFilter") ?? "";
    get classNameFilter(): string {
        return this._classNameFilter;
    }
    set classNameFilter(value) {
        storage.setItem("classNameFilter", value);
        this._classNameFilter = value;
    }

    oninit() {
        this.resetHistoryDepth();
        this.getEiAndGenOutput();
        this.getLoadFiles();
        this.getLoadSessions();
        this.getTestCov();
    }

    // Returns a promise to allow us to await on the result on this request
    updateEi(): Promise<void> {
        let arr = Array.from(this.newEiChoices.entries()).map(([index, choice]) => ({
            index,
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
            // TODO temporary hack here that refetches history every time
            // eventually have API return all state updates in single object
            m.request({
                method: "GET",
                url: SERVER_URL + "/history",
            }),
            // Get generator output
            // Mithril type defs don't yet have responseType, but excluding will cause it to just try and fail to read JSON
            // @ts-ignore
            m.request({
                method: "GET",
                url: SERVER_URL + "/generator",
                responseType: "text",
            }),
            // Get new EI
            m.request({
                method: "GET",
                url: SERVER_URL + "/ei",
            })
                .then((arr: LocWithData[]) => arr)
                .catch(e =>
                    [{ei: "ERROR " + e.message, typeInfo: ChoiceKind.BYTE, choice: 0, stackTrace: [], used: false}]
                )
        ])
            .then(([history, genOutput, eiData]: [FuzzHistory, string, LocWithData[]]) => {
                this.history = history;
                this.resetHistoryDepth();
                this.currRunInfo = {
                    eiTableData: eiData,
                    genOutput,
                };
            });
    }

    getTestCov() {
        m.request({
            method: "GET",
            url: SERVER_URL + "/run_test",
        })
            .then((result: TestCov) => {
                this.testCov = result;
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

    getLoadSessions() {
        m.request({
            method: "GET",
            url: SERVER_URL + "/load_session",
        })
            .then((files: string[]) => {
                this.availableLoadSessions = files;
            });
    }

    view() {
        return (
            <>
                <table id="controlPanel">
                    <caption style={{textAlign: "left", fontWeight: "bold"}}>Controls</caption>
                    <thead>
                        <th>Generator</th>
                        <th>Test Coverage</th>
                        <th>View</th>
                        <th>Session</th>
                    </thead>
                    <tbody>
                        <tr>
                            {/* generator */}
                            <td>
                                <button type="submit" onclick={() =>
                                    this.updateEi()
                                        .then(() => this.postGenOutput())
                                        .then(() => this.getEiAndGenOutput())
                                }>
                                    Rerun generator
                                </button>
                                <div>
                                    <form id="saveInputForm" onsubmit={(e: Event) => {
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
                                    <form id="loadInputForm" method="POST" onsubmit={(e: Event) => {
                                        let loadFileName = this.loadFileName;
                                        e.preventDefault();
                                        if (loadFileName) {
                                            m.request({
                                                method: "POST",
                                                url: SERVER_URL + "/load_input",
                                                body: {fileName: loadFileName},
                                            })
                                                .then(() => console.log("Loaded file", loadFileName))
                                                .then(() => Promise.all([this.getEiAndGenOutput(), this.getLoadSessions()]));
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
                                        <button type="submit">Load</button>
                                    </form>
                                </div>
                            </td>

                            {/* test coverage */}
                            <td>
                                <button type="submit" onclick={() =>
                                    m.request({
                                        method: "POST",
                                        url: SERVER_URL + "/run_test"
                                    })
                                        .then((result: TestCov) => {
                                            this.testCov = result;
                                        })
                                }>
                                    Rerun Test Case
                                </button>
                            </td>

                            {/* view */}
                            <td>
                                <label>
                                    Show type-level decisions:{" "}
                                    <input type="checkbox" id="displayTyped" checked={this.displayTypedEi}
                                           oninput={(e: Event) => {
                                               this.displayTypedEi= (e.target as HTMLInputElement).checked;
                                           }}
                                    />
                                </label>
                                <br />
                                <label>
                                    Show unused locations:{" "}
                                    <input type="checkbox" id="showUnused" checked={this.showUnused}
                                           oninput={(e: Event) => {
                                               this.showUnused = (e.target as HTMLInputElement).checked;
                                           }}
                                    />
                                </label>
                                <br />
                                <label>
                                    Filter stack trace by class name:{" "}
                                    <input type="text" id="classNameFilter" value={this.classNameFilter} oninput={(e: Event) => {
                                        this.classNameFilter = (e.target as HTMLInputElement).value;
                                    }}
                                    />
                                </label>
                                <br />
                                <label>
                                    Display format:{" "}
                                    <select value={this.byteRender} onchange={(e: Event) => {
                                        this.byteRender = ((e.target!! as HTMLSelectElement).value) as ByteRender;
                                    }}>
                                        {Object.values(ByteRender).map(v => <option value={v}>{v}</option>)}
                                    </select>
                                </label>
                                <div>
                                    <label>
                                        View history:{" "}
                                        {/* Left = further back in history = increment*/}
                                        <button onclick={
                                            () => this.historyDepth = Math.min(this.historyDepth + 1, this.history.runResults.length)
                                        }>&lt;--</button>
                                        {/* Right = more recent in history = decrement*/}
                                        <button onclick={
                                            () => this.historyDepth = Math.max(this.historyDepth - 1, 1)
                                        }>--&gt;</button>
                                    </label>
                                </div>
                            </td>

                            {/* session */}
                            <td>
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

                                <div>
                                    <form id="saveSessionForm" onsubmit={(e: Event) => {
                                        let saveFileName = this.saveSessionName;
                                        e.preventDefault();
                                        m.request({
                                            method: "POST",
                                            url: SERVER_URL + "/save_session",
                                            body: {fileName: saveFileName},
                                        }).then(() => console.log("Saved session", saveFileName, "(probably)"));
                                        this.saveSessionName = undefined;
                                    }}>
                                        <label>
                                            Save current session to file:{" "}
                                            <input type="text" value={this.saveSessionName} required oninput={(e: InputEvent) =>
                                                this.saveSessionName = (e.target!! as HTMLInputElement).value
                                            }/>
                                        </label>
                                        <button type="submit">Save</button>
                                    </form>
                                    <form id="loadSessionForm" method="POST" onsubmit={(e: Event) => {
                                        let loadFileName = this.loadSessionName;
                                        e.preventDefault();
                                        if (loadFileName) {
                                            m.request({
                                                method: "POST",
                                                url: SERVER_URL + "/load_session",
                                                body: {fileName: loadFileName},
                                            })
                                                .then((history: FuzzHistory) => {
                                                    console.log("Loaded session", loadFileName);
                                                    this.history = history;
                                                    this.resetHistoryDepth();
                                                })
                                                .then(() => Promise.all([this.getEiAndGenOutput(), this.getLoadSessions()]));
                                            this.loadSessionName = undefined;
                                        }
                                    }}>
                                        <label>
                                            Load session from file:{" "}
                                            <select value={this.loadSessionName} onchange={(e: Event) => {
                                                this.loadSessionName = (e.target!! as HTMLSelectElement).value;
                                            }}>
                                                <option value=""/>
                                                {
                                                    this.availableLoadSessions?.map((fileName: string) =>
                                                        <option value={fileName}>{fileName}</option>)
                                                }
                                            </select>
                                        </label>
                                        <button type="submit">Load</button>
                                    </form>
                                </div>
                            </td>
                        </tr>
                    </tbody>
                </table>

                <br />
                <table>
                    <tr>
                        <th style={{
                                fontWeight: (this.activeTab === ActiveTab.EI) ? "bold" : "normal",
                                cursor: "pointer",
                                textDecoration: this.genTabMouseOver ? "underline" : undefined
                        }}
                            onclick={() => this.activeTab = ActiveTab.EI}
                            onmouseover={() => this.genTabMouseOver = true}
                            onmouseout={() => this.genTabMouseOver = false}
                        >
                            Generator Data
                        </th>
                        <th style={{
                                fontWeight: (this.activeTab === ActiveTab.COV) ? "bold" : "normal",
                                cursor: "pointer",
                                textDecoration: this.covTabMouseOver ? "underline" : undefined
                            }}
                            onclick={() => this.activeTab = ActiveTab.COV}
                            onmouseover={() => this.covTabMouseOver = true}
                            onmouseout={() => this.covTabMouseOver = false}
                        >
                            Coverage Data
                        </th>
                    </tr>
                </table>
                {
                    this.activeTab === ActiveTab.EI ? (
                        <table>
                            <tbody>
                            <tr>
                                <td>
                                    <div style={{ overflow: "auto" }}>
                                    {this.displayTypedEi
                                        ? <EiTypedDisplay typedData={addTypeInfo(this.currRunInfo.eiTableData)}
                                                          locList={this.history.locList}
                                                          history={this.history} historyDepth={this.historyDepth}
                                                          historicChoices={getHistoricChoices(this.history, this.historyDepth)}
                                                          showUnused={this.showUnused}
                                                          renderer={(n, bounds?) => n == null ? "--" : renderNumber(n, this.byteRender, bounds)}
                                                          newEiChoices={this.newEiChoices}
                                                          classNameFilter={this.classNameFilter} />
                                        : <EiByteDisplay eiTableData={this.currRunInfo.eiTableData}
                                                         newEiChoices={this.newEiChoices}
                                                         history={this.history}
                                                         historyDepth={this.historyDepth}
                                                         historicChoices={getHistoricChoices(this.history, this.historyDepth)}
                                                         showUnused={this.showUnused}
                                                         renderer={(n) => n == null ? "--" : renderNumber(n, this.byteRender)}
                                                         classNameFilter={this.classNameFilter} />
                                    }
                                    </div>
                                </td>
                                <td>
                                    <GenOutputDisplay currOutput={this.currRunInfo.genOutput}
                                                      prevOutput={this.history.runResults[this.history.runResults.length - this.historyDepth - 1]?.serializedResult ?? ""}
                                                      ago={this.historyDepth}/>
                                </td>
                            </tr>
                            </tbody>
                        </table>
                    ) : (
                        <>
                            <span><b>Test Status:</b> {this.testCov.status}</span>
                            <table>
                                {this.testCov.cov.split("\n").filter(row => row.length > 0).map(
                                    (row, i) => <tr>{row.split(",").map(
                                        e => i === 0 ? (<th>{e}</th>) : <td>{e}</td>
                                    )}</tr>)
                                }
                            </table>
                        </>
                    )
                }
            </>
        );
    }
}
