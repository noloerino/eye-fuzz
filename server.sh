#!/usr/bin/env bash
# Runs the server. Assumes "mvn package" has been run to build the jar.
JAR_PATH='target/eye-fuzz-1.0-SNAPSHOT-jar-with-dependencies.jar'
java -jar -javaagent:./jacocoagent.jar $JAR_PATH $@
