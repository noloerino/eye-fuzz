
let generatorStale = false;
let coverageStale = false;

const SERVER_URL = "http://localhost:8000"

let eiTableBody;
let genTableBody;
let genContentCell;
let covTableBody;
let rerunGenForm;

window.onload = () => {
    rerunGenForm = document.getElementById("rerunGenForm");
    eiTableBody = document.getElementById("eiTableBody");
    genTableBody = document.getElementById("generatorTableBody");
    genContentCell = document.getElementById("genContentCell");
    covTableBody = document.getElementById("coverageTableBody");
    init();
};

let eiTableData = [
    {ei: "sample ei", data: 255},
    {ei: "sample ei 2", data: 100},
];

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
            eiTableData = [];
            if (status === 0 || (status >= 200 && status < 400)) {
                for (let line of req.responseText.split("\n")) {
                    let spaceIndex = line.indexOf(" ");
                    let data = line.slice(0, spaceIndex);
                    let ei = line.slice(spaceIndex + 1);
                    eiTableData.push({ei: ei, data: data});
                }
            } else {
                eiTableData.push({ei: "ERROR " + status, data: 0});
            }
            // Update DOM
            // https://stackoverflow.com/questions/7271490/delete-all-rows-in-an-html-table
            let newTBody = document.createElement("tbody");
            for (let {ei, data} of eiTableData) {
                console.log("new table data: " + ei + " " + data);
                let newRow = newTBody.insertRow(-1);
                let cell0 = newRow.insertCell(0);
                cell0.innerText = ei;
                let cell1 = newRow.insertCell(1);
                let dataInput = document.createElement("input")
                dataInput.type = "number";
                dataInput.min = "0";
                dataInput.max = "255";
                dataInput.value = data.data;
                cell1.appendChild(dataInput);
            }
            eiTableBody.parentNode.replaceChild(newTBody, eiTableBody);
            eiTableBody = newTBody;
        }

    };
    req.send();
};

let getGenerator = function() {
    let req = new XMLHttpRequest();
    req.open("GET", SERVER_URL + "/generator");
    req.onreadystatechange = () => {
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
    req.onerror = (e) => {
        genOutput = e;
    };
    req.send();
};

let postGenerator = function() {
    
};

let init = function () {
    for (let entry of covTableData) {
        let newRow = covTableBody.insertRow(-1); // at end of table
        let cell0 = newRow.insertCell(0);
        cell0.innerText = entry;
    }

    getEi();
    getGenerator();
};
