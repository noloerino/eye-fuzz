
let generatorStale = false;
let coverageStale = false;

let eiTableBody;
let genTableBody = document.getElementById("generatorTableBody");
let covTableBody = document.getElementById("coverageTableBody");

window.onload = () => {
    eiTableBody = document.getElementById("eiTableBody");
    genTableBody = document.getElementById("generatorTableBody");
    covTableBody = document.getElementById("coverageTableBody");
    init();
};

let eiTableData = [
    {ei: "sample ei", data: 255},
    {ei: "sample ei 2", data: 100},
];

let genOutput = "var k_0; for { }";
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
        cell1.innerText = entry.data;
    }

    let genRow = genTableBody.insertRow(-1);
    let genCell = genRow.insertCell(0);
    genCell.innerText = genOutput;

    for (let entry of covTableData) {
        let newRow = covTableBody.insertRow(-1); // at end of table
        let cell0 = newRow.insertCell(0);
        cell0.innerText = entry;
    }
};
