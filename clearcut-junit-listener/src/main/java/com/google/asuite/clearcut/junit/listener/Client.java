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

import com.android.asuite.clearcut.Clientanalytics.ClientInfo;
import com.android.asuite.clearcut.Clientanalytics.LogEvent;
import com.android.asuite.clearcut.Clientanalytics.LogRequest;
import com.android.asuite.clearcut.Clientanalytics.LogResponse;
import com.android.asuite.clearcut.Common.UserType;

import com.google.common.base.Strings;
import com.google.protobuf.util.JsonFormat;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Client that allows reporting usage metrics to clearcut. */
public class Client {

    public static final String DISABLE_CLEARCUT_KEY = "DISABLE_CLEARCUT";
    private static final String CLEARCUT_SUB_TOOL_NAME = "CLEARCUT_SUB_TOOL_NAME";

    private static final String CLEARCUT_PROD_URL = "https://play.googleapis.com/log";
    private static final int CLIENT_TYPE = 1;
    private static final int INTERNAL_LOG_SOURCE = 971;
    private static final int EXTERNAL_LOG_SOURCE = 934;

    private static final long SCHEDULER_INITIAL_DELAY_MILLISECONDS = 1000;
    private static final long SCHEDULER_PERDIOC_MILLISECONDS = 250;

    private static final String GOOGLE_EMAIL = "@google.com";
    private static final String GOOGLE_HOSTNAME = ".google.com";

    private File mCachedUuidFile = new File(System.getProperty("user.home"), ".clearcut_listener");
    private String mRunId;
    private long mSessionStartTime = 0L;

    private final int mLogSource;
    private final String mUrl;
    private final UserType mUserType;
    private final String mSubToolName;

    // Consider synchronized list
    private final List<LogRequest> mExternalEventQueue;
    // The pool executor to actually post the metrics
    private ScheduledThreadPoolExecutor mExecutor;
    // Whether the clearcut client should be a noop
    private boolean mDisabled = false;

    public Client(String subToolName) {
        this(null, subToolName);
        Runtime.getRuntime().addShutdownHook(new Thread(Client.this::stop));
    }

    /**
     * Create Client with customized posting URL and forcing whether it's internal or external user.
     */
    protected Client(String url, String subToolName) {
        mDisabled = isClearcutDisabled();

        // We still have to set the 'final' variable so go through the assignments before returning
        if (!mDisabled && isGoogleUser()) {
            mLogSource = INTERNAL_LOG_SOURCE;
            mUserType = UserType.GOOGLE;
        } else {
            mLogSource = EXTERNAL_LOG_SOURCE;
            mUserType = UserType.EXTERNAL;
        }
        if (url == null) {
            mUrl = CLEARCUT_PROD_URL;
        } else {
            mUrl = url;
        }
        mRunId = UUID.randomUUID().toString();
        mExternalEventQueue = new ArrayList<>();
        if (Strings.isNullOrEmpty(subToolName) && System.getenv(CLEARCUT_SUB_TOOL_NAME) != null) {
            mSubToolName = System.getenv(CLEARCUT_SUB_TOOL_NAME);
        } else {
            mSubToolName = subToolName;
        }

        if (mDisabled) {
            return;
        }

        // Print the notice
        System.out.println(NoticeMessageUtil.getNoticeMessage(mUserType));

        // Executor to actually send the events.
        mExecutor =
                new ScheduledThreadPoolExecutor(
                        1,
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = Executors.defaultThreadFactory().newThread(r);
                                t.setDaemon(true);
                                t.setName("clearcut-client-thread");
                                return t;
                            }
                        });
        Runnable command =
                new Runnable() {
                    @Override
                    public void run() {
                        flushEvents();
                    }
                };
        mExecutor.scheduleAtFixedRate(
                command,
                SCHEDULER_INITIAL_DELAY_MILLISECONDS,
                SCHEDULER_PERDIOC_MILLISECONDS,
                TimeUnit.MILLISECONDS);
    }

    /** Send the first event to notify that Tradefed was started. */
    public void notifyTradefedStartEvent() {
        if (mDisabled) {
            return;
        }
        mSessionStartTime = System.nanoTime();
        long eventTimeMs = System.currentTimeMillis();
        CompletableFuture.supplyAsync(() -> createStartEvent(eventTimeMs));
    }

    private boolean createStartEvent(long eventTimeMs) {
        LogRequest.Builder request = createBaseLogRequest();
        LogEvent.Builder logEvent = LogEvent.newBuilder();
        logEvent.setEventTimeMs(eventTimeMs);
        logEvent.setSourceExtension(
                ClearcutEventHelper.createStartEvent(
                        getGroupingKey(), mRunId, mUserType, mSubToolName));
        request.addLogEvent(logEvent);
        queueEvent(request.build());
        return true;
    }

    /** Send the last event to notify that Tradefed is done. */
    public void notifyTradefedFinishedEvent() {
        if (mDisabled) {
            return;
        }
        Duration duration = java.time.Duration.ofNanos(System.nanoTime() - mSessionStartTime);
        LogRequest.Builder request = createBaseLogRequest();
        LogEvent.Builder logEvent = LogEvent.newBuilder();
        logEvent.setEventTimeMs(System.currentTimeMillis());
        logEvent.setSourceExtension(
                ClearcutEventHelper.createFinishedEvent(
                        getGroupingKey(), mRunId, mUserType, mSubToolName, duration));
        request.addLogEvent(logEvent);
        queueEvent(request.build());
    }

    /** Send the event to notify that a Tradefed invocation was started. */
    public void notifyTradefedInvocationStartEvent() {
        if (mDisabled) {
            return;
        }
        LogRequest.Builder request = createBaseLogRequest();
        LogEvent.Builder logEvent = LogEvent.newBuilder();
        logEvent.setEventTimeMs(System.currentTimeMillis());
        logEvent.setSourceExtension(
                ClearcutEventHelper.createRunStartEvent(
                        getGroupingKey(), mRunId, mUserType, mSubToolName));
        request.addLogEvent(logEvent);
        queueEvent(request.build());
    }

    /** Send the event to notify that a test run finished. */
    public void notifyTestRunFinished(long startTimeNano) {
        if (mDisabled) {
            return;
        }
        Duration duration = java.time.Duration.ofNanos(System.nanoTime() - startTimeNano);
        LogRequest.Builder request = createBaseLogRequest();
        LogEvent.Builder logEvent = LogEvent.newBuilder();
        logEvent.setEventTimeMs(System.currentTimeMillis());
        logEvent.setSourceExtension(
                ClearcutEventHelper.creatRunTestFinished(
                        getGroupingKey(), mRunId, mUserType, mSubToolName, duration));
        request.addLogEvent(logEvent);
        queueEvent(request.build());
    }

    /** Stop the periodic sending of clearcut events */
    public void stop() {
        if (mExecutor != null) {
            mExecutor.setRemoveOnCancelPolicy(true);
            mExecutor.shutdown();
            mExecutor = null;
        }
        // Send all remaining events
        flushEvents();
    }

    /** Add an event to the queue of events that needs to be send. */
    public void queueEvent(LogRequest event) {
        synchronized (mExternalEventQueue) {
            mExternalEventQueue.add(event);
        }
    }

    /** Returns the current queue size. */
    public final int getQueueSize() {
        synchronized (mExternalEventQueue) {
            return mExternalEventQueue.size();
        }
    }

    /** Allows to override the default cached uuid file. */
    public void setCachedUuidFile(File uuidFile) {
        mCachedUuidFile = uuidFile;
    }

    /** Get a new or the cached uuid for the user. */
    String getGroupingKey() {
        String uuid = null;
        if (mCachedUuidFile.exists()) {
            try {
                uuid = readFromFile(mCachedUuidFile);
            } catch (IOException e) {
                logError(e);
            }
        }
        if (uuid == null || uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString();
            try {
                writeToFile(uuid, mCachedUuidFile);
            } catch (IOException e) {
                logError(e);
            }
        }
        return uuid;
    }

    /** Returns True if clearcut is disabled, False otherwise. */
    public boolean isClearcutDisabled() {
        return "1".equals(System.getenv(DISABLE_CLEARCUT_KEY));
    }

    /** Returns True if the user is a Googler, False otherwise. */
    boolean isGoogleUser() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname.contains(GOOGLE_HOSTNAME)) {
                return true;
            }
        } catch (UnknownHostException e) {
            // Ignore
        }
        Optional<String> result = executCommand("git", "config", "--get", "user.email");
        if (result.isPresent()) {
            String stdout = result.get();
            if (stdout != null && stdout.trim().endsWith(GOOGLE_EMAIL)) {
                return true;
            }
        }
        return false;
    }

    private LogRequest.Builder createBaseLogRequest() {
        LogRequest.Builder request = LogRequest.newBuilder();
        request.setLogSource(mLogSource);
        request.setClientInfo(ClientInfo.newBuilder().setClientType(CLIENT_TYPE));
        return request;
    }

    private void flushEvents() {
        List<LogRequest> copy = new ArrayList<>();
        synchronized (mExternalEventQueue) {
            copy.addAll(mExternalEventQueue);
            mExternalEventQueue.clear();
        }
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        while (!copy.isEmpty()) {
            LogRequest event = copy.remove(0);
            futures.add(CompletableFuture.supplyAsync(() -> sendToClearcut(event)));
        }

        for (CompletableFuture<Boolean> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                logError(e);
            }
        }
    }

    /** Send one event to the configured server. */
    private boolean sendToClearcut(LogRequest event) {
        InputStream inputStream = null;
        InputStream errorStream = null;
        OutputStream outputStream = null;
        OutputStreamWriter outputStreamWriter = null;
        try {
            HttpURLConnection connection = createConnection(new URI(mUrl).toURL(), "POST", "text");
            outputStream = connection.getOutputStream();
            outputStreamWriter = new OutputStreamWriter(outputStream);

            String jsonObject = JsonFormat.printer().preservingProtoFieldNames().print(event);
            outputStreamWriter.write(jsonObject.toString());
            outputStreamWriter.flush();

            inputStream = connection.getInputStream();
            LogResponse response = LogResponse.parseFrom(inputStream);

            errorStream = connection.getErrorStream();
            if (errorStream != null) {
                String message =  readStream(errorStream);
                System.out.println("Error posting clearcut event: " + message + " LogResponse: " + response);
            }
        } catch (IOException | URISyntaxException e) {
            logError(e);
        } catch (NoSuchMethodError e) {
            if (e.getMessage().contains("com.google.protobuf.Descriptors$Descriptor com.google.protobuf.Any.getDescriptor()")) {
                StringBuilder message = new StringBuilder();
                message.append("In order for the ClearcutListener to operate it must use protobuf-full to be able to convert messages to json.\n");
                message.append("Android typically uses protobuf-lite.");
                message.append("If you're seeing this in a gradle build, adding `testImplementation(project(\":RobolectricLib\"))` to the start of ");
                message.append("your dependency section should be sufficient, if not (due to how gradle calculates deps), add this dep: ");
                message.append("`testImplementation(libs.protobuf.java)` to the top of your dependencies");
                throw new RuntimeException(message.toString(), e);
            } else {
                logError(e);
                throw e;
            }
        } catch (Throwable t) {
            logError(t);
            throw t;
        } finally {
            closeQuietly(outputStream);
            closeQuietly(inputStream);
            closeQuietly(outputStreamWriter);
            closeQuietly(errorStream);
        }
        return true;
    }

    private void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (IOException ex) {
            // Intentional No-Op
        }
    }

    private void logError(Throwable t) {
        System.out.println(t);
        t.printStackTrace(System.out);
    }

    protected static String readFromFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }

    protected static void writeToFile(String content, File file) throws IOException {
        Files.writeString(file.toPath(), content, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    }

    protected static String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    protected static Optional<String> executCommand(String... command) {
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
                    Arrays.stream(command).collect(Collectors.joining(" ")));
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private static HttpURLConnection createConnection(URL url, String method, String contentType)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setConnectTimeout(60 * 1000);  // timeout for establishing the connection
        connection.setReadTimeout(60 * 1000);  // timeout for receiving a read() response
        connection.setRequestProperty("User-Agent",
                String.format("%s/%s", "TradeFederation_like_ClearcutJunitListener", "1.0"));

        return connection;
    }
}
