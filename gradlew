#!/bin/sh

# Gradle wrapper script for MyTTS

# Resolve the app home directory
APP_HOME=$( cd "${0%"${0##*/}"}." && pwd -P ) || exit

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine Java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if [ ! -x "$JAVACMD" ] ; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2
    echo "Please set JAVA_HOME to point to your JDK installation." >&2
    exit 1
fi

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
