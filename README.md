# eye-fuzz
A tool for visualizing the relationship between generative fuzzing inputs and code coverage.

## How it works
The frontend maps from a stack trace to a byte used in a decision within the generator. The frontend connects to a
local Java HTTP server, and performs updates to those bytes and the resulting program output.

## Building
The following build tools are needed:
- [Maven](https://maven.apache.org/install.html)
- [yarn](https://classic.yarnpkg.com/en/docs/install/)
This project has been configured to build and run under JDK/JRE 8 - it may work on newer versions of Java, but
it has not been tested.

### Building
After cloning this repository, run the following two commands:
```bash
mvn package       # Builds the server backend
cd client && yarn # Builds the frontend UI
```

Once you've done so, you're ready to try out your generator!

### Running
First, write your generator and test driver in a separate directory (see the
[Zest wiki](https://github.com/rohanpadhye/JQF/wiki/Fuzzing-with-Zest)) for an example of how to do this.

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
    `com.google.javascript.jscomp.Compiler,com.google.javascript.jscomp.CompilerOptions` (coverage is automatically
    collected on the test case class; pass empty quotes to specify no additional classes)
- \[optional\] `SERIALIZER_CLASS`: the name of a class containing a method to serialize the generator output
- \[optional\] `SERIALIZER_FUNC`: the name of a method to transform the generator output into a human-readable string

Lastly, open `client/index.html` to begin fuzzing.

### Usage
Once the server is running, you will be able to see values returned by various calls to `random()`. To modify values
and rerun the generator, fill in values corresponding to the desired function call(s) under the "New value" column,
and press "Rerun generator" in the top left. The generator output should be updated accordingly.

To view test coverage for the current run, move to the "Coverage Data" tab. This will display a coverage summary
(produced by [Jacoco](https://www.jacoco.org/jacoco/)). It will also display whether the generator produced a valid
input to the test method in the "Test Status" field - an invalid input will show `INVALID`, whereas a valid input
would show `SUCCESS`, `FAILURE`, or `TIMEOUT` depending on the result of the test run.

### With JQF/Zest
To load an input (whether previously saved by eye-fuzz or produced by a Zest fuzzing session), drag the file into the
`savedInputs` directory. Once you refresh the page, select the file name from the "Load input file" dropdown, and then
hit `load`.

### Settings and Controls
- **Generator Controls**
    - _Rerun generator_: Reruns the generator with the new values you specified.
    - _Save last input to file_: Saves the sequence of bytes that produced the last generator result to a file. This
        file is just a binary sequence that can be fed to any generator, and can be used with Zest's ReproGuidance.
        
        The file will be saved to the `savedInputs` directory.
    - _Load input file_: Loads a sequence of bytes to be used for a generator. The available options will be the list of
        files in `savedInputs`; to load a file from elsewhere, move it into the `savedInputs` folder and refresh the page.
- **Test Coverage Controls**
    - _Rerun test case_: This button reruns the test case using the most recent generator output, updating the collected
        coverage data.
- **View Settings**
    - _Show type-level decisions_: When checked, instead of displaying a single entry for each individual byte, a
        single row is displayed for each call to a `random()` function.
        
        For example, suppose a call to `randInt()` produced the value `0xABCD1234`. When this option is checked,
        `0xABCD1234` will be displayed; when the option is not, then there will be four entries containing the values
        `0x34`, `0x12`, `0xCD`, and `0xAB` (from top to bottom).
        
        Note also that if a `random()` call was made with min or max bounds, type-level information will display the
        value returned to the generator rather than simply combining the bytes together. If a call `randInt(3, 7)` were
        to produce the raw bytes `0xABCD1234`, the actual displayed value would be `3`, as the bytes were clamped
        into the range \[3, 7).
    - _Show unused locations_: When checked, stack traces that weren't reached on this run (but were reached on previous
        ones) are shown.
    - _Filter stack trace by class name_: Provides a string to filter out displayed stack traces. When not empty, only
        lines in stack traces that contain the specified string (case-sensitive) are shown.
    - _Display format_: Chooses how to display values (decimal, binary, hexadecimal, or UTF-16 codepoint).
    - _View history_: The left arrow will cause the display to show values and generator output from progressively older
        runs, while the right arrow will show progressively newer ones.
- **Session Controls**
    - _Restart from scratch_: Hitting this button will completely reset the state of your fuzzing session.
    - _Save current session to file_: Saves the whole history of choices made for the duration of a fuzzing session
        to a JSON file with the specified name (the `.json` extension is not automatically applied).
        
        The file will be saved to the `savedSessions` directory.
    - _Load session from file_: Loads a history of choices from a JSON file, as produced by the "Save current session"
        command. This history will be accessible, and the generator will be rerun once to ensure consistency.
