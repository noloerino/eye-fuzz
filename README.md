# eye-fuzz
A tool for visualizing the relationship between generator inputs and code coverage.

## How it works
A simple frontend maps from a JQF `ExecutionIndex` (essentially a stack trace that identifies where a random byte
was used in generating the fuzzer input) to a byte used in a decision. The frontend connects to a simple local
Java HTTP server, and POSTs/GETs updates to those bytes and the resulting program output.

## Running
### JVM flags to paste into Intellij configuration for `Server`
Assuming JQF is built from source in parent dir:
```
-Xbootclasspath/a:"../JQF/instrument/target/classes:../JQF/instrument/target/jqf-instrument-1.6.jar:../JQF/instrument/target/dependency/asm-8.0.1.jar"
-javaagent:"../JQF/instrument/target/jqf-instrument-1.6.jar"
-Djanala.conf="../JQF/scripts/janala.conf"
```

### Running the thing
- In Intellij, run the `Server` class with the aforementioned VM flags
- Open `index.html` in a browser. The "Rerun Generator" button will perform a POST request with the data in the
  input fields as payload. Changes to the EI map should appear automatically in the frontend (you may need to scroll
  down a bit).
