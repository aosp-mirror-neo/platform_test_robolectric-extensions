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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class EnvironmentInformation {

    // LINT.IfChange
    private static final List<String> GOOGLE_HOSTNAMES = Arrays.asList(".google.com", "c.googlers.com");
    // LINT.ThenChange(/tools/asuite/atest/constants_default.py)
    private static String ROBOLECTRIC_SYSUI_EXTENSION_CLASS = "com.google.android.sysui.ToTSdkProvider";
    private static String GRADLE = "worker.org.gradle.process.internal.worker.GradleWorkerMain";
    private static String ROBOLECTRIC_CLASS = "org.robolectric.Robolectric";
    private static final String GOOGLE_EMAIL = "@google.com";

    private static boolean hasClassInLoader(String className) {
        try {
            Thread.currentThread().getContextClassLoader().loadClass(
                    className);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    public static boolean isSysUIRoboTest() {
        return hasClassInLoader(ROBOLECTRIC_SYSUI_EXTENSION_CLASS);
    }

    public static boolean isGradleTest() {
        return hasClassInLoader(GRADLE);
    }

    public static boolean isRoboTest() {
        return hasClassInLoader(ROBOLECTRIC_CLASS);
    }

    public static boolean isDebugging() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().
                getInputArguments().toString().contains("-agentlib:jdwp");
    }


    public static Optional<String> getGitEmail() {
        return executeCommand("git", "config", "--get", "user.email");
    }

    /**
     * @return Optional of git username, if email and ends in @google.com
     */
    public static Optional<String> getGitUserIfGoogleEmail(Optional<String> email) {
        if (email.isPresent()) {
            String emailStr = email.get();
            if (emailStr.trim().endsWith(GOOGLE_EMAIL)) {
                return Optional.of(emailStr.trim().replace(GOOGLE_EMAIL, ""));
            }
        }
        return Optional.empty();
    }

    static Optional<String> executeCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            if (process.waitFor(60, TimeUnit.SECONDS)) {
                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    return Optional.of(output.toString());
                }
            }
        } catch (IOException | InterruptedException e) {
            System.out.println(Client.class.getName() + " could not execute command:" +
                    String.join(" ", command));
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public static boolean isGoogleDomain() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (GOOGLE_HOSTNAMES.stream().anyMatch(hostname::endsWith)) {
                return true;
            }
        } catch (UnknownHostException e) {
            System.err.println("Could not determine if google host: " + e.getMessage());
        }
        return false;
    }
}
