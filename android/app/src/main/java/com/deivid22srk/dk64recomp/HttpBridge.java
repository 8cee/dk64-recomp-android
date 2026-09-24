package com.deivid22srk.dk64recomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * Small HTTP(S) bridge used by the native libcurl compatibility layer.
 *
 * RecompFrontend's mod discovery only performs GET requests. Delegating TLS to
 * HttpURLConnection keeps certificate verification on Android's platform trust
 * store instead of bundling a second TLS stack in the APK. Responses are first
 * streamed to an app-private temporary file so large mod downloads never have
 * to be held in a Java byte array; native code then feeds that file through the
 * normal libcurl write callback.
 */
final class HttpBridge {
    private static final String TAG = "DK64Recomp";
    private static volatile Context appContext;

    // Keep these values in sync with android/native/stubs/include/curl/curl.h.
    private static final int CURLE_OK = 0;
    private static final int CURLE_UNSUPPORTED_PROTOCOL = 1;
    private static final int CURLE_FAILED_INIT = 2;
    private static final int CURLE_COULDNT_RESOLVE_HOST = 6;
    private static final int CURLE_COULDNT_CONNECT = 7;
    private static final int CURLE_OPERATION_TIMEDOUT = 28;
    private static final int CURLE_PEER_FAILED_VERIFICATION = 60;

    private HttpBridge() {}

    private static native void nativeInit();

    static void init(Context context) {
        appContext = context.getApplicationContext();
        try {
            nativeInit();
            Log.i(TAG, "HTTP bridge initialized");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "HTTP bridge JNI unavailable: " + e.getMessage());
        }
    }

    /**
     * Native contract: [curlCode, httpStatus, tempPath, errorMessage].
     *
     * A non-2xx HTTP status is still CURLE_OK, matching libcurl semantics;
     * callers inspect CURLINFO_RESPONSE_CODE afterwards.
     */
    @SuppressWarnings("unused") // Called from native code through JNI.
    private static String[] performGet(String urlString,
                                       int timeoutMs,
                                       boolean followRedirects,
                                       String userAgent,
                                       String[] headers) {
        Context context = appContext;
        if (context == null) {
            return result(CURLE_FAILED_INIT, 0, "", "HTTP bridge is not initialized");
        }

        HttpURLConnection connection = null;
        File temp = null;
        try {
            URL url = new URL(urlString);
            if (!"http".equalsIgnoreCase(url.getProtocol())
                    && !"https".equalsIgnoreCase(url.getProtocol())) {
                return result(CURLE_UNSUPPORTED_PROTOCOL, 0, "",
                        "Unsupported URL protocol: " + url.getProtocol());
            }

            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(followRedirects);
            connection.setUseCaches(false);
            connection.setConnectTimeout(Math.max(timeoutMs, 1));
            connection.setReadTimeout(Math.max(timeoutMs, 1));
            connection.setRequestMethod("GET");

            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }

            if (headers != null) {
                for (String header : headers) {
                    if (header == null) continue;
                    int colon = header.indexOf(':');
                    if (colon <= 0) continue;
                    String name = header.substring(0, colon).trim();
                    String value = header.substring(colon + 1).trim();
                    if (!name.isEmpty()) connection.setRequestProperty(name, value);
                }
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            temp = File.createTempFile("dk64-http-", ".tmp", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(temp, false)) {
                if (input != null) {
                    try (InputStream in = input) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            if (n > 0) output.write(buffer, 0, n);
                        }
                    }
                }
                output.flush();
            }

            return result(CURLE_OK, status, temp.getAbsolutePath(), "");
        } catch (SocketTimeoutException e) {
            deleteQuietly(temp);
            return result(CURLE_OPERATION_TIMEDOUT, 0, "", message(e));
        } catch (UnknownHostException e) {
            deleteQuietly(temp);
            return result(CURLE_COULDNT_RESOLVE_HOST, 0, "", message(e));
        } catch (SSLException e) {
            deleteQuietly(temp);
            return result(CURLE_PEER_FAILED_VERIFICATION, 0, "", message(e));
        } catch (ConnectException e) {
            deleteQuietly(temp);
            return result(CURLE_COULDNT_CONNECT, 0, "", message(e));
        } catch (ClassCastException e) {
            deleteQuietly(temp);
            return result(CURLE_UNSUPPORTED_PROTOCOL, 0, "", message(e));
        } catch (IOException e) {
            deleteQuietly(temp);
            return result(CURLE_COULDNT_CONNECT, 0, "", message(e));
        } catch (Throwable t) {
            deleteQuietly(temp);
            return result(CURLE_FAILED_INIT, 0, "", message(t));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String[] result(int curlCode, int httpStatus, String path, String error) {
        return new String[]{
                Integer.toString(curlCode),
                Integer.toString(httpStatus),
                path == null ? "" : path,
                error == null ? "" : error
        };
    }

    private static String message(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isEmpty())
                ? t.getClass().getSimpleName()
                : message;
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Throwable ignored) { }
        }
    }
}
