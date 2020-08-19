
let generatorStale = false;
let coverageStale = false;

let eiTableBody;
let genTableBody;
let covTableBody;
let rerunGenForm;

window.onload = () => {
    rerunGenForm = document.getElementById("rerunGenForm");
    eiTableBody = document.getElementById("eiTableBody");
    genTableBody = document.getElementById("generatorTableBody");
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

let init = function () {
    for (let entry of eiTableData) {
        let newRow = eiTableBody.insertRow(-1); // at end of table
        let cell0 = newRow.insertCell(0);
        cell0.innerText = entry.ei;
        let cell1 = newRow.insertCell(1);
        let dataInput = document.createElement("input")
        dataInput.type = "number";
        dataInput.min = "0";
        dataInput.max = "255";
        dataInput.value = entry.data;
        cell1.appendChild(dataInput);
    }

    let genRow = genTableBody.insertRow(-1);
    let genCell = genRow.insertCell(0);
    genCell.innerText = genOutput;

    for (let entry of covTableData) {
        let newRow = covTableBody.insertRow(-1); // at end of table
        let cell0 = newRow.insertCell(0);
        cell0.innerText = entry;
    }

    let req = new XMLHttpRequest();
    req.open("GET", "http://localhost:8000/generator", false);
    req.onreadystatechange = () => {
        if(req.readyState === XMLHttpRequest.DONE) {
            let status = req.status;
            if (status === 0 || (status >= 200 && status < 400)) {
                genOutput = req.responseText;
            } else {
                genOutput = "ERROR";
            }
        }
    };
    req.send();
};
