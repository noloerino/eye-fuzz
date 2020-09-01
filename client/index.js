
let generatorStale = false;
let coverageStale = false;

const SERVER_URL = "http://localhost:8000"

let eiTableBody;
let genTableBody;
let genContentCell;
let covTableBody;
let rerunGen;

window.onload = () => {
    rerunGen = document.getElementById("rerunGenForm");
    eiTableBody = document.getElementById("eiTableBody");
    genTableBody = document.getElementById("generatorTableBody");
    genContentCell = document.getElementById("genContentCell");
    covTableBody = document.getElementById("coverageTableBody");
    init();
};

let genOutput = "";
let covTableData = [
    "(hash) line of code --> other code",
    "(hash) me call"
];

let getEi = function() {
    let req = new XMLHttpRequest();
    req.open("GET", SERVER_URL + "/ei");
    req.onreadystatechange = () => {
        if (req.readyState === XMLHttpRequest.DONE) {
            let status = req.status;
            let eiTableData = [];
            if (status === 0 || (status >= 200 && status < 400)) {
                for (let line of req.responseText.split("\n")) {
                    if (line.length > 0) {
                        let [data, event, ei] = line.split(":");
                        eiTableData.push({ei: ei, data: data, event: event});
                    }
                }
            } else {
                eiTableData.push({ei: "ERROR " + status, data: 0, event: ""});
            }
            // Update DOM
            // https://stackoverflow.com/questions/7271490/delete-all-rows-in-an-html-table
            let newTBody = document.createElement("tbody");
            newTBody.id = "eiTableBody";
            for (let {ei, data, event} of eiTableData) {
                // console.log("new table data: " + ei + " " + data);
                let newRow = newTBody.insertRow(-1);
                let cell0 = newRow.insertCell(0);
                cell0.innerText = ei;
                cell0.className = "ei-cell";
                let cell1 = newRow.insertCell(1);
                cell1.innerText = event;
                let cell2 = newRow.insertCell(2);
                let dataInput = document.createElement("input");
                dataInput.type = "number";
                dataInput.min = "0";
                dataInput.max = "255";
                dataInput.value = data;
                cell2.appendChild(dataInput);
            }
            eiTableBody.parentNode.replaceChild(newTBody, eiTableBody);
            eiTableBody = newTBody;
        }
    };
    req.send();
};

let postEi = function() {
    let req = new XMLHttpRequest();
    req.open("POST", SERVER_URL + "/ei");
    let body = "";
    // Read from DOM since underlying state may be outdated
    let rows = eiTableBody.rows;
    for (let i = 0; i < rows.length; i++) {
        let row = rows[i];
        let ei = row.cells[0].innerText;
        // Read input cell
        let data = row.cells[1].childNodes[0].value;
        body += data + " " + ei + "\n";
    }
    req.setRequestHeader("Content-Type", "text/plain");
    req.onreadystatechange = () => {
        if (req.readyState === XMLHttpRequest.DONE) {
            let status = req.status;
            if (status === 0 || (status >= 200 && status < 400)) {
                postGenerator();
            }
        }

    };
    req.send(body);
};

let updateGenOutput = function(req) {
    if (req.readyState === XMLHttpRequest.DONE) {
        let status = req.status;
        if (status === 0 || (status >= 200 && status < 400)) {
            genOutput = req.responseText;
        } else {
            genOutput = "ERROR " + status;
        }
    }
    genContentCell.innerText = genOutput;
};

let getGenerator = function() {
    let req = new XMLHttpRequest();
    req.open("GET", SERVER_URL + "/generator");
    req.onreadystatechange = () => {
        updateGenOutput(req)
    };
    req.onerror = (e) => {
        genOutput = e;
    };
    req.send();
};

let postGenerator = function() {
    let req = new XMLHttpRequest();
    req.open("POST", SERVER_URL + "/generator");
    req.onreadystatechange = () => {
        updateGenOutput(req);
        // May need to update EI as well
        getEi();
    };
    req.onerror = (e) => {
        genOutput = e;
    };
    req.send();
};

let init = function () {
    for (let entry of covTableData) {
        let newRow = covTableBody.insertRow(-1); // at end of table
        let cell0 = newRow.insertCell(0);
        cell0.innerText = entry;
    }
    getEi();
    getGenerator();
    rerunGen.addEventListener("submit", (e) => {
        e.preventDefault();
        postEi();
    });
};
