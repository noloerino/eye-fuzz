# eye-fuzz
A tool for visualizing the relationship between generator inputs and code coverage.

## How it works
A simple frontend maps from a stack trace to a byte used in a decision. The frontend connects to a simple local
Java HTTP server, and POSTs/GETs updates to those bytes and the resulting program output.

`mvn package` builds a fat JAR to run the server. `yarn start` from the `client` directory builds the frontend.

## Build and Run
First, write your generator and test driver in a separate directory (see the
[Zest wiki](https://github.com/rohanpadhye/JQF/wiki/Fuzzing-with-Zest)) for an example of how to do this.

Run the following two commands to build the backend and frontend for this server:
```
mvn package                 # Build the server backend
cd client && yarn build     # Build the frontend
```
TODO package produces jar, but what produces class files?

Next, start up the server with the following commands:
```
./server.sh [-c <classpath>] <args> 
```
where `<classpath>` is the build directory containing your JAR or class files of the test driver and generator.
`<args>` are as follows, in order:
- `GENERATOR_CLASS_NAME`: the name of the generator class
- `TEST_CLASS_NAME`: the name of the test driver class
- `TEST_METHOD_NAME`: the name of the method on the test driver to be invoked
- `COVERAGE_CLASS_NAMES`: comma-separate list of class names for Jacoco to collect coverage on, for example
    `com.google.javascript.jscomp.Compiler,com.google.javascript.jscomp.CompilerOptions`
- \[optional\] `SERIALIZER_CLASS`: a class containing a method to serialize the generator output
- \[optional\] `SERIALIZER_FUNC`: a method to transform the generator output into a human-readable string

Lastly, open `client/index.html` to begin fuzzing.

