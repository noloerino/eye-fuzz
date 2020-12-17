# eye-fuzz
A tool for visualizing the relationship between generative fuzzing inputs and code coverage.

## How it works
JQF generators operate by consuming a stream of pseudorandom bytes, which are then used to produce the values returned
by `Random` methods like `nextInt()` and `nextChar()`.
The `eye-fuzz` frontend shows the program locations (stack traces) at which these random bytes are consumed, and
allows you to modify those values to influence the generator output to your liking.

## Building
### Prerequisites
Compilation requires the following build tools:
- [Maven](https://maven.apache.org/install.html)
- [yarn](https://classic.yarnpkg.com/en/docs/install/)

This project is configured to build and run under JDK/JRE 8 - it may work on newer versions of Java, but it has not
been tested.

### Compiling
After cloning this repository, run the following two commands:
```bash
mvn package       # Builds the server backend
cd client && yarn # Builds the frontend UI
```

Once you've done so, you're ready to try out your generator!

## Running
First, write your generator and test driver in a separate directory (see the
[Zest wiki](https://github.com/rohanpadhye/JQF/wiki/Fuzzing-with-Zest) for an example of how to do this).

Next, start up the server with the following command:
```
./server.sh [-c <classpath>] <args> 
```
where `<classpath>` is the build directory containing your JAR or class files of the test driver and generator.
This will start a local HTTP server on port 8000 that will track the state of your fuzzing session.

`<args>` are as follows, in order:
- `GENERATOR_CLASS_NAME`: the name of the generator class
- `TEST_CLASS_NAME`: the name of the test driver class
- `TEST_METHOD_NAME`: the name of the method on the test driver to be invoked
- `COVERAGE_CLASS_NAMES`: comma-separate list of class names for Jacoco to collect coverage on (coverage is automatically
    collected on the test case class; pass empty quotes to specify no additional classes)
    
For example, suppose you compiled JQF's [calendar tutorial](https://github.com/rohanpadhye/JQF/wiki/Fuzzing-with-Zest)
in the folder `../calendar-example`. To start the fuzzing server and collect coverage for the `CalendarLogic` and
`CalendarGenerator` classes, you would run the following command:
```
./server.sh -c ../calendar-example/ CalendarGenerator CalendarTest testLeapYear "CalendarLogic,CalendarGenerator"
```

Lastly, open `client/index.html` to begin fuzzing.

### Usage
Once the server is running, you can see the values returned by `Random` method calls in the generator. To modify these
values and rerun the generator, fill in values corresponding to the desired function call(s) under the "New Value"
column, and press "Rerun generator" in the top left. The generator output should be updated accordingly.

To view test coverage for the current run, move to the "Coverage Data" tab. This displays a code coverage summary
produced by [Jacoco](https://www.jacoco.org/jacoco/). It also indicates whether the generator produced a valid
input to the test method in the "Test Status" field - an input that fails test preconditions or constraints will show
`INVALID`, whereas a valid input shows `SUCCESS`, `FAILURE`, or `TIMEOUT` depending on the result of the test run.

### Replaying a JQF/Zest-Generated Input
To load an input (whether previously saved by eye-fuzz or produced by a Zest fuzzing session), drag the file into the
`saved_inputs` directory. After refreshing the page, select the file name from the "Load input file" dropdown and hit
`Load`.

## Settings and Controls
### Generator Controls
![Generator Controls](/demo_images/gen-settings.jpg)
- _Rerun generator_: Reruns the generator with the new values specified.
- _Save last input to file_: Saves the sequence of bytes that produced the last generator result to a file. This
    file is just a binary sequence that can be fed to any generator, and can be used with Zest's ReproGuidance.
    
    The file is saved in the `saved_inputs` directory.
- _Load input file_: Loads a sequence of bytes to be used for a generator from a file in the `saved_inputs` directory.
    To load a file from elsewhere in your filesystem, move or copy it into the `saved_inputs` folder and refresh the page.
    
### Test Coverage Controls
![Test Controls](/demo_images/test-settings.jpg)
- _Rerun test case_: Reruns the test case using the most recent generator output, updating the collected coverage data.

### View Settings
![View Controls](/demo_images/view-settings.jpg)
These options control how program locations and their corresponding values are displayed and formatted.
- _Show type-level decisions_: When checked, instead of displaying a single entry for each individual byte, a
    single row is displayed for each call to a `Random` method.
    
    For example, suppose a call to `randInt()` produces the value `0xABCD1234`. When this option is checked,
    `0xABCD1234` is displayed; otherwise it shows four entries containing the values `0x34`, `0x12`, `0xCD`, and `0xAB`
    (from top to bottom).
    
    Note also that if a `Random` call is made with min or max bounds, type-level information will display the
    value returned to the generator rather than simply combining the bytes together. If a call `randInt(3, 7)` were
    to produce the raw bytes `0xABCD1234`, the actual displayed value would be `3`, as the bytes are clamped
    into the range \[3, 7).
- _Show unused locations_: When checked, stack traces that weren't reached on this run (but were reached on previous
    ones) are shown.
- _Filter stack trace by class name_: Provides a string to filter lines from the displayed stack traces. When not empty,
    only lines in stack traces that contain the specified string (case-sensitive) are shown.
- _Display format_: Chooses how to format numeric values (decimal, binary, hexadecimal, or UTF-16 codepoint).
- _View history_: Hitting the left arrow causes the display to show values and generator output from progressively older
    runs, while hitting the right arrow shows progressively newer ones.
    
### Session Controls
![Session Controls](/demo_images/session-settings.jpg)
These options let you save, load, and reset an `eye-fuzz` fuzzing session, which captures the full history of changes
in values and generator outputs made by the user.
- _Restart from scratch_: Hitting this button completely resets the state of your fuzzing session. The generator will
    be reseeded with a fresh set of bytes.
- _Save current session to file_: Saves the whole history of choices made for the duration of a fuzzing session
    to a JSON file with the specified name (the `.json` extension is not automatically applied).
    
    The file is saved in the `saved_sessions` directory.
- _Load session from file_: Loads a history of choices from a JSON file, as produced by the "Save current session"
    command. This replaces the current fuzzing session, and the generator will be rerun once to ensure consistency.
    
## Limitations
`eye-fuzz` may encounter difficulties in the following conditions.

### Unsupported `Random` functions
`eye-fuzz` supports all methods implemented by JUnit Quickcheck's `SourceOfRandomness` API (found
[here](https://pholser.github.io/junit-quickcheck/site/1.0/junit-quickcheck-core/apidocs/com/pholser/junit/quickcheck/random/SourceOfRandomness.html),
except the following:
- `nextBigInteger`
- `nextInstant`
- `nextDuration`

Additionally, the following methods can be manipulated at the byte level by the frontend, but are not displayed properly
with type or bounds information:
- `nextBytes`
- `nextFloat`
- `nextDouble`
- `nextGaussian`

If your use case requires support for any of these methods, please file an issue.

### Multiple lines with the same stack trace
Because it is impossible to distinguish between function calls with identical stack traces, `eye-fuzz` may
encounter unexpected behavior in tracking history when faced with such a scenario (behavior for modifying and generating
new bytes should be unaffected). Identical stack traces may occur if two calls to the same `Random` method are placed on
the same line (for example, `f(randInt(), randInt()))`), or if a `Random` method call occurs in the body of a for loop.

### Generators with stateful caching behavior
If a generator caches intermediate computations that invoke `Random` methods, then rerunning the generator twice with
the same underlying byte stream may produce different results (this is a limitation of JQF as well). More broadly, any
generator whose output is not solely a function of the input pseudorandom byte stream will exhibit this behavior.

### Custom serializers for generators
Currently, the generator output displayed on the frontend is simply the result of calling `toString()` on the produced
object (truncated to 1024 characters). Custom serialization functions are supported but not yet exposed to the
command-line interface - please file an issue if this is a feature you need.
