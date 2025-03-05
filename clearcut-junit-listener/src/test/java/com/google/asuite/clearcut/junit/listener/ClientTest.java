/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/** Unit tests for {@link Client}. */
@RunWith(JUnit4.class)
public class ClientTest {

    private Client mClient;

    @Before
    public void setUp() {
        mClient =
                new Client("url", "test") {
                    @Override
                    boolean isGoogleUser() {
                        return false;
                    }
                };
    }

    @After
    public void tearDown() {
        mClient.stop();
    }

    @Test
    public void testDisableClient() {
        Client c =
                new Client("url", "test") {
                    @Override
                    public boolean isClearcutDisabled() {
                        return true;
                    }

                    @Override
                    boolean isGoogleUser() {
                        throw new RuntimeException("Should not be called if disabled");
                    }
                };
        try {
            c.notifyTradefedStartEvent();
            c.notifyTradefedStartEvent();
            c.notifyTradefedStartEvent();
        } finally {
            c.stop();
        }
    }
}
