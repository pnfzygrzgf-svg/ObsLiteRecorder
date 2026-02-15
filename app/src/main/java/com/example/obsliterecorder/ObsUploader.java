package com.example.obsliterecorder;

import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class ObsUploader {

    private static final String TAG = "ObsUploader";

    public interface ProgressListener {
        void onProgress(float progress);
    }

    /**
     * CookieJar das Cookies aus dem Android WebView CookieManager liest.
     * Damit werden die Session-Cookies vom Portal-Login verwendet.
     */
    private final CookieJar webViewCookieJar = new CookieJar() {
        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            CookieManager cookieManager = CookieManager.getInstance();
            for (Cookie cookie : cookies) {
                cookieManager.setCookie(url.toString(), cookie.toString());
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookieString = cookieManager.getCookie(url.toString());
            if (cookieString == null || cookieString.isEmpty()) {
                return new ArrayList<>();
            }

            List<Cookie> cookies = new ArrayList<>();
            String[] cookieParts = cookieString.split(";");
            for (String part : cookieParts) {
                Cookie cookie = Cookie.parse(url, part.trim());
                if (cookie != null) {
                    cookies.add(cookie);
                }
            }
            return cookies;
        }
    };

    private final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(webViewCookieJar)
            .build();

    /**
     * Upload einer Track-Datei (z.B. BIN) zum OBS-Lite-Server.
     *
     * @param trackFile Die Datei, die hochgeladen werden soll (BIN, GPX, …)
     * @param baseUrl   Basis-URL des OBS-Portals (z.B. https://meinserver oder https://meinserver/api/tracks)
     * @param apiKey    OBS API-Key
     */
    public UploadResult uploadTrack(File trackFile, String baseUrl, String apiKey) throws IOException {
        String obsLiteUrl = normalizeObsUrl(baseUrl);

        Log.d(TAG, "uploadTrack: file=" + trackFile.getAbsolutePath() +
                ", exists=" + trackFile.exists() +
                ", size=" + trackFile.length() +
                ", url=" + obsLiteUrl);

        if (!trackFile.exists()) {
            Log.e(TAG, "uploadTrack: File does not exist!");
            return new UploadResult(0, "Datei existiert nicht: " + trackFile.getAbsolutePath());
        }

        MediaType mediaType = MediaType.parse("application/octet-stream");
        RequestBody fileBody = RequestBody.create(trackFile, mediaType);

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("body", trackFile.getName(), fileBody)
                .build();

        // Zuerst mit Session-Cookies versuchen (vom Portal-Login)
        Request request = new Request.Builder()
                .url(obsLiteUrl)
                .post(multipartBody)
                .build();

        Log.d(TAG, "uploadTrack: trying with session cookies...");

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "uploadTrack: response code=" + code + ", body=" + body);

            // Falls 401 und API-Key vorhanden, nochmal mit API-Key versuchen
            if (code == 401 && apiKey != null && !apiKey.isEmpty()) {
                Log.d(TAG, "uploadTrack: 401 with cookies, retrying with API key...");
                return uploadWithApiKey(trackFile, obsLiteUrl, apiKey);
            }

            return new UploadResult(code, body);
        }
    }

    /**
     * Upload mit API-Key Header (Fallback wenn Session-Cookies nicht funktionieren)
     */
    private UploadResult uploadWithApiKey(File trackFile, String url, String apiKey) throws IOException {
        MediaType mediaType = MediaType.parse("application/octet-stream");
        RequestBody fileBody = RequestBody.create(trackFile, mediaType);

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("body", trackFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(multipartBody)
                .addHeader("Authorization", "OBSUserId " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "uploadWithApiKey: response code=" + code + ", body=" + body);
            return new UploadResult(code, body);
        }
    }

    private String normalizeObsUrl(String baseUrl) {
        String url = baseUrl;
        if (!(url.endsWith("/api/tracks") || url.endsWith("/api/tracks/"))) {
            if (url.endsWith("/")) {
                url += "api/tracks";
            } else {
                url += "/api/tracks";
            }
        }
        return url;
    }

    /**
     * Upload mit Fortschritts-Callback.
     */
    public UploadResult uploadTrack(File trackFile, String baseUrl, String apiKey, ProgressListener listener) throws IOException {
        String obsLiteUrl = normalizeObsUrl(baseUrl);

        if (!trackFile.exists()) {
            return new UploadResult(0, "Datei existiert nicht: " + trackFile.getAbsolutePath());
        }

        MediaType mediaType = MediaType.parse("application/octet-stream");
        RequestBody fileBody = new ProgressRequestBody(trackFile, mediaType, listener);

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("body", trackFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(obsLiteUrl)
                .post(multipartBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";

            if (code == 401 && apiKey != null && !apiKey.isEmpty()) {
                return uploadWithApiKey(trackFile, obsLiteUrl, apiKey, listener);
            }

            return new UploadResult(code, body);
        }
    }

    private UploadResult uploadWithApiKey(File trackFile, String url, String apiKey, ProgressListener listener) throws IOException {
        MediaType mediaType = MediaType.parse("application/octet-stream");
        RequestBody fileBody = new ProgressRequestBody(trackFile, mediaType, listener);

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("body", trackFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(multipartBody)
                .addHeader("Authorization", "OBSUserId " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";
            return new UploadResult(code, body);
        }
    }

    /**
     * RequestBody das Fortschritt meldet.
     */
    private static class ProgressRequestBody extends RequestBody {
        private final File file;
        private final MediaType contentType;
        private final ProgressListener listener;

        ProgressRequestBody(File file, MediaType contentType, ProgressListener listener) {
            this.file = file;
            this.contentType = contentType;
            this.listener = listener;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return file.length();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            long totalBytes = file.length();
            long bytesWritten = 0;

            try (Source source = Okio.source(file)) {
                long read;
                while ((read = source.read(sink.getBuffer(), 8192)) != -1) {
                    bytesWritten += read;
                    sink.flush();
                    if (listener != null && totalBytes > 0) {
                        listener.onProgress((float) bytesWritten / totalBytes);
                    }
                }
            }
        }
    }

    public static class UploadResult {
        public final int statusCode;
        public final String responseBody;

        public UploadResult(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
