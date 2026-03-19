#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Licensed under the Apache License, Version 2.0
#
# Gradle wrapper shell script for POSIX systems.

# Attempt to set APP_HOME
APP_HOME="${0%"${0##*/}"}"
APP_HOME=$(cd "${APP_HOME:-./}" && pwd -P) || exit

APP_NAME="Gradle"
APP_BASE_NAME="${0##*/}"

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in
  CYGWIN* ) cygwin=true ;;
  Darwin  ) darwin=true ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
    fi
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )
    CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )
    JAVACMD=$( cygpath --unix "$JAVACMD" )
fi

# Collect all arguments for the java command;
# * $DEFAULT_JVM_OPTS, $JAVA_OPTS, and $GRADLE_OPTS can contain fragments of
#   shell script including quotes and variable substitutions.
set -- \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "$@"
