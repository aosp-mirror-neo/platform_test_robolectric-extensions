/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.asuite.clearcut.junit.listener;

import com.google.auto.service.AutoService;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

@AutoService(RunListener.class)
public class ClearcutJunitListener extends RunListener {

    private Client client;
    private long startTime = System.nanoTime();

    public ClearcutJunitListener() {
        System.out.println("In clearcut listener");
        String context = "junit";
        if (EnvironmentInformation.isSysUIRoboTest()) { //sysui
            context = "junit_sysui";
        } else if (EnvironmentInformation.isGradleTest()) { //intellij
            context = "junit_gradle";
        } else if (EnvironmentInformation.isRoboTest()) { //robolectric
            context += "_robolectric";
        }
        client = new Client(context);
    }


    @Override
    public void testRunStarted(Description description) throws Exception {
        client.notifyTradefedStartEvent();
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        client.notifyTradefedFinishedEvent();
    }

    @Override
    public void testStarted(Description description) throws Exception {
        client.notifyTradefedInvocationStartEvent();
    }

    public void testFinished(Description description) throws Exception {
        client.notifyTestRunFinished(startTime);
        client.stop();
    }
}
