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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/** Client that allows reporting usage metrics to clearcut. */
public class Client {

    public static final String DISABLE_CLEARCUT_KEY = "DISABLE_CLEARCUT";
    private static final String CLEARCUT_SUB_TOOL_NAME = "CLEARCUT_SUB_TOOL_NAME";

    private static final String CLEARCUT_PROD_URL = "https://play.googleapis.com/log";
    private static final int CLIENT_TYPE = 1;
    private static final int INTERNAL_LOG_SOURCE = 971;
    private static final int EXTERNAL_LOG_SOURCE = 934;
    private File mCachedUuidFile = new File(System.getProperty("user.home"), ".clearcut_listener");
    private String mRunId;
    private long mSessionStartTime = 0L;
    private final int mLogSource;
    private final String mUrl;
    private final UserType mUserType;
    private final String mToolName;
    private final String mSubToolName;
    private final String mUser;
    private final boolean mIsGoogle;

    // Whether the clearcut client should be a noop
    private boolean mDisabled = false;

    public Client(String toolName, String subToolName) {
        this(null, toolName, subToolName);
        Runtime.getRuntime().addShutdownHook(new Thread(Client.this::stop));
    }

    /**
     * Create Client with customized posting URL and forcing whether it's internal or external user.
     */
    protected Client(String url, String toolName, String subToolName) {
        mDisabled = isClearcutDisabled();
        Optional<String> email = EnvironmentInformation.getGitEmail();
        Optional<String> googleUser = EnvironmentInformation.getGitUserIfGoogleEmail(email);
        mIsGoogle = EnvironmentInformation.isGoogleDomain() ||
                googleUser.isPresent();
        Optional<String> username = EnvironmentInformation.executeCommand("whoami");

        // We still have to set the 'final' variable so go through the assignments before returning
        if (!mDisabled && isGoogleUser()) {
            mLogSource = INTERNAL_LOG_SOURCE;
            mUserType = UserType.GOOGLE;
            mUser = googleUser.orElse(username.orElse(""));
        } else {
            mLogSource = EXTERNAL_LOG_SOURCE;
            mUserType = UserType.EXTERNAL;
            mUser = UUID5.uuidOf(UUID5.NAMESPACE_DNS, email.orElse(username.orElse(""))).toString();
        }
        mUrl = Objects.requireNonNullElse(url, CLEARCUT_PROD_URL);
        mToolName = toolName;
        mRunId = UUID.randomUUID().toString();
        if (subToolName != null && subToolName.isEmpty() && System.getenv(CLEARCUT_SUB_TOOL_NAME) != null) {
            mSubToolName = System.getenv(CLEARCUT_SUB_TOOL_NAME);
        } else {
            mSubToolName = subToolName;
        }

        if (mDisabled) {
            return;
        }

        // Print the notice
        System.out.println(NoticeMessageUtil.getNoticeMessage(mUserType));
    }

    protected void disable(){
        mDisabled = true;
    }

    boolean isGoogleUser() {
        return mIsGoogle;
    }

    /** Send the first event to notify that Tradefed was started. */
    public void notifyTradefedStartEvent() {
        if (mDisabled) {
            return;
        }
        mSessionStartTime = System.nanoTime();
        long eventTimeMs = System.currentTimeMillis();
        CompletableFuture.supplyAsync(() -> createAndSendStartEvent(eventTimeMs));
    }

    private boolean createAndSendStartEvent(long eventTimeMs) {
        LogEvent.Builder logEvent = LogEvent.newBuilder();
        logEvent.setEventTimeMs(eventTimeMs);
        logEvent.setSourceExtension(
                ClearcutEventHelper.createStartEvent(
                        mUser, mRunId, mUserType, mToolName, mSubToolName));
        LogRequest.Builder request = createBaseLogRequest();
        request.addLogEvent(logEvent);
        sendEvent(request.build());
        return true;
    }

    public void notifyTradefedFinishedEvent() {
        if (mDisabled) {
            return;
        }
        CompletableFuture.supplyAsync(() -> createAndSendFinishedEvent());
    }

    /** Send the last event to notify that Tradefed is done. */
    public boolean createAndSendFinishedEvent() {
        Duration duration = java.time.Duration.ofNanos(System.nanoTime() - mSessionStartTime);
        LogEvent.Builder logEvent = LogEvent.newBuilder();
        logEvent.setEventTimeMs(System.currentTimeMillis());
        logEvent.setSourceExtension(
                ClearcutEventHelper.createFinishedEvent(
                        mUser, mRunId, mUserType, mToolName, mSubToolName, duration));
        LogRequest.Builder request = createBaseLogRequest();
        request.addLogEvent(logEvent);
        sendEvent(request.build());
        return true;
    }

    /** Stop the periodic sending of clearcut events */
    public void stop() {
        notifyTradefedFinishedEvent();
    }

    /** Returns True if clearcut is disabled, False otherwise. */
    public boolean isClearcutDisabled() {
        return "1".equals(System.getenv(DISABLE_CLEARCUT_KEY));
    }

    private LogRequest.Builder createBaseLogRequest() {
        LogRequest.Builder request = LogRequest.newBuilder();
        request.setLogSource(mLogSource);
        request.setClientInfo(ClientInfo.newBuilder().setClientType(CLIENT_TYPE));
        return request;
    }

    private void sendEvent(LogRequest request) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> sendToClearcut(request));
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            logError(e);
        }
    }

    /** Send one event to the configured server. */
    private boolean sendToClearcut(LogRequest event) {
        InputStream inputStream = null;
        InputStream errorStream = null;
        OutputStream outputStream = null;
        try {
            HttpURLConnection connection = createConnection(new URI(mUrl).toURL(), "POST", null);
            connection.setRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("Content-Type", "application/x-gzip");
            outputStream = connection.getOutputStream();
            outputStream.write(gzipCompress(event.toByteArray()));
            outputStream.flush();

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
                String message =
                        "In order for the ClearcutListener to operate it must use protobuf-full to be able to convert messages to json.\n"
                                + "Android typically uses protobuf-lite."
                                + "If you're seeing this in a gradle build, adding `testImplementation(project(\":RobolectricLib\"))` to the start of "
                                + "your dependency section should be sufficient, if not (due to how gradle calculates deps), add this dep: "
                                + "`testImplementation(libs.protobuf.java)` to the top of your dependencies";
                throw new RuntimeException(message, e);
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

    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(data);
        }
        return outputStream.toByteArray();
    }
}
