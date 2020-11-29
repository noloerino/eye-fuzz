#!/usr/bin/env bash
            
# Runs the server. Assumes "mvn package" has been run to build the jar.
JAR_PATH='target/eye-fuzz-1.0-SNAPSHOT-jar-with-dependencies.jar'
if [ ! -f "$JAR_PATH" ]; then
    echo "Server jar class path ($JAR_PATH) has not been built."
    echo "Please run 'mvn package' first."
    exit 1
fi

JS_PATH='client/bin/app.js'
if [ ! -f "$JS_PATH" ]; then
    echo "Frontend has not been built."
    echo "Please run 'cd client && yarn build' first."
    exit 1
fi

print_usage() {
    echo "Usage: $0 [-c CLASSPATH] GENERATOR_CLASS_NAME TEST_CLASS_NAME TEST_METHOD_NAME [SERIALIZER_CLASS] [SERIALIZER_FUNC]"
}

CLASSPATH=".:$JAR_PATH"
while getopts ":c:" opt; do
    case $opt in
        /?)
            echo "Invalid option -$OPTARG" >&2
            print_usage >&1
            exit 1
            ;;
        c)
            CLASSPATH="$CLASSPATH:$OPTARG"
    esac
done
shift $((OPTIND-1))

# Since -jar and -cp are mutually exclusive, we just invoke MainKt and place the jar on the classpath
MAIN_PATH='MainKt'
java -ea \
    -cp "$CLASSPATH" \
    -javaagent:./jacocoagent.jar \
    "$MAIN_PATH" \
    $@
