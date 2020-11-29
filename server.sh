#!/usr/bin/env bash
            
# Runs the server. Assumes "mvn package" has been run to build the jar.
JAR_PATH='target/eye-fuzz-1.0-SNAPSHOT-jar-with-dependencies.jar'
if [ ! -e "$JAR_PATH" ]; then
    echo "Server jar (at $JAR_PATH) has not been built"
    echo "Please run 'mvn package' first."
    exit 1
fi

print_usage() {
    echo "Usage: $0 [-c CLASSPATH] GENERATOR_CLASS_NAME TEST_CLASS_NAME TEST_METHOD_NAME [SERIALIZER_CLASS] [SERIALIZER_FUNC]"
}

while getopts ":c:" opt; do
    case $opt in
        /?)
            echo "Invalid option -$OPTARG" >&2
            print_usage >&1
            exit 1
            ;;
        c)
            export CLASSPATH="$OPTARG"
    esac
done
shift $((OPTIND-1))

java -jar -javaagent:./jacocoagent.jar $JAR_PATH $@
