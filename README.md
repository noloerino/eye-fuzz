# eye-fuzz
A tool for visualizing the relationship between generator inputs and code coverage.

## How it works
A simple frontend maps from a stack trace to a byte used in a decision. The frontend connects to a simple local
Java HTTP server, and POSTs/GETs updates to those bytes and the resulting program output.

`mvn package` builds a fat JAR to run the server. `yarn start` from the `client` directory builds the frontend.

### Running the thing
- Run the shell script (will update with more later probably)
- Open `index.html` in a browser. The "Rerun Generator" button will perform a POST request with the data in the
  input fields as payload. Changes to the EI map should appear automatically in the frontend (you may need to scroll
  down a bit).
