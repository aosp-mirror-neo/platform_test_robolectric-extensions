#!/bin/bash

#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Experimental, simple script to run a robolectric test in AOSP development environment.
# Intended for faster iteration and more specific options than atest.
#
# In particular this script will:
#  - only run tests on current SDK (35)
#  - use sqlite native mode
#  - load native libandroid_runtime built from HEAD
#  - output stdout and stderr directly to terminal
#
# Usage:
# [m -j <test_module_name> libandroid_runtime]
# run-android-test.sh <test_module_name> <test_class_name>

set -e

if [[ ${ANDROID_HOST_OUT_TESTCASES:-"unset"} == "unset" ]]; then
    echo "ERROR: android build environment not initialized. Run build/envsetup.sh and lunch."
    exit -1
fi

if [ $# -lt 2 -o $# -gt 2 ]; then
  echo "ERROR: invalid number of arguments. Usage: run-android-test.sh <test_module_name> <test_class_name>."
  exit -1
fi

MODULE_NAME=$1
CLASS_NAME=$2

# uncomment this to wait for debugger to attach
#DEBUGGER=' -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000 '
DEBUGGER=' '

# android build system integration assumes working directory = test case directory. See https://robolectric.org/build-system-integration/
cd $ANDROID_HOST_OUT_TESTCASES/$MODULE_NAME

java -cp $ANDROID_HOST_OUT_TESTCASES/$MODULE_NAME/$MODULE_NAME.jar:$ANDROID_HOST_OUT_TESTCASES/android-all/android-all-current-robolectric-r0.jar \
    -Drobolectric.dependency.dir=$ANDROID_HOST_OUT_TESTCASES/android-all \
    -Drobolectric.logging=stdout \
    -Drobolectric.logging.enabled=true \
    -Drobolectric.offline=true \
    -Drobolectric.resourcesMode=BINARY \
    -Drobolectric.usePreinstrumentedJars=false \
    -Drobolectric.enabledSdks=35 \
    -Drobolectric.alwaysIncludeVariantMarkersInTestName=true \
    -Dandroid.robolectric.loadLibraryFromPath=true \
    -Djava.library.path=$ANDROID_HOST_OUT/lib64:/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib \
    -Drobolectric.sqliteMode=NATIVE \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    $DEBUGGER \
    org.junit.runner.JUnitCore \
    $CLASS_NAME
