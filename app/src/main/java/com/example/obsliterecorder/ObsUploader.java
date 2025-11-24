package com.example.obsliterecorder;

import java.io.File;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ObsUploader {

    private final OkHttpClient client = new OkHttpClient();

    /**
     * Upload einer Track-Datei (z.B. BIN) zum OBS-Lite-Server.
     *
     * @param trackFile Die Datei, die hochgeladen werden soll (BIN, GPX, …)
     * @param baseUrl   Basis-URL des OBS-Portals (z.B. https://meinserver oder https://meinserver/api/tracks)
     * @param apiKey    OBS API-Key
     */
    public UploadResult uploadTrack(File trackFile, String baseUrl, String apiKey) throws IOException {
        String obsLiteUrl = normalizeObsUrl(baseUrl);

        MediaType mediaType = MediaType.parse("application/octet-stream");
        RequestBody fileBody = RequestBody.create(trackFile, mediaType);

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("body", trackFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(obsLiteUrl)
                .post(multipartBody)
                .addHeader("Authorization", "OBSUserId " + apiKey)
                .addHeader("content-type", "multipart/form-data;")
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";
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
