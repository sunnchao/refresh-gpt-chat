package com.refresh.gptChat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.refresh.gptChat.pojo.Conversation;
import com.refresh.gptChat.pojo.Image;
import com.refresh.gptChat.pojo.Result;
import com.refresh.gptChat.pojo.Speech;
import com.refresh.gptChat.service.processService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Yangyang
 * @create 2024-04-08 9:47
 */

@Slf4j
@Service
public class processServiceImpl implements processService {
    /**
     * utf-8类型
     */
    private static final MediaType mediaType = MediaType.
            parse("application/json; charset=utf-8");
    /**
     * 定义正则
     */
    private static final Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
    /**
     * okhttp3 client服务定义
     */
    private final OkHttpClient client = new OkHttpClient.Builder().
            connectTimeout(3, TimeUnit.MINUTES).
            readTimeout(5, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).build();

    @Autowired
    private tokenServiceImpl tokenService;
    @Autowired
    private outPutServiceImpl outPutService;
    /**
     * 画图使用的模型 gpt-4-mobile 或者 gpt-4
     */
    @Value("${image_mobel}")
    private String image_mobel;

    /**
     * /v1/chat/completions
     * 如发现token过期
     * 重新回复问题
     */
    @Override
    public void chatManageUnsuccessfulResponse(ConcurrentHashMap<String, String> refreshTokenList,
                                               Response resp,
                                               String refreshToken,
                                               HttpServletResponse response,
                                               Conversation conversation,
                                               String chatUrl,
                                               String requestId) {
        switch (resp.code()) {
            case 429:
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
            case 401:
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "access_token is wrong");
            case 404:
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "404");
            case 500:
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL SERVER ERROR");
            default:
                String token = refreshToken;
                if (!refreshToken.startsWith("eyJhb")) {
                    token = tokenService.getAccessToken(refreshToken);
                    if (token == null) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh_token is wrong");
                    }
                    refreshTokenList.put(refreshToken, token);
                    log.info("assess_token过期，refreshTokenList重置化成功！");
                }
                againChatConversation(response, conversation, token, chatUrl, requestId);
        }
    }

    /**
     * /v1/chat/completions
     * 重新回复问题
     */
    public Object againChatConversation(HttpServletResponse response,
                                        @org.springframework.web.bind.annotation.RequestBody Conversation conversation,
                                        String access_token,
                                        String chatUrl,
                                        String request_id) {
        Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
        try {
            String json = com.alibaba.fastjson2.JSON.toJSONString(conversation);
            RequestBody requestBody = RequestBody.create(json, mediaType);
            log.info("请求回复接口：" + chatUrl);
            Request.Builder requestBuilder = new Request.Builder().url(chatUrl).post(requestBody);
            headersMap.forEach(requestBuilder::addHeader);
            Request streamRequest = requestBuilder.build();
            try (Response resp = client.newCall(streamRequest).execute()) {
                if (resp.isSuccessful()) {
                    // 流式和非流式输出
                    outPutService.outPutChat(response, resp, conversation);
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * /v1/images/generations
     * 如发现token过期
     * 重新回复问题
     */
    public void imageManageUnsuccessfulResponse(ConcurrentHashMap<String, String> refreshTokenList,
                                                Response resp,
                                                String refresh_token,
                                                HttpServletResponse response,
                                                @org.springframework.web.bind.annotation.RequestBody Image conversation,
                                                String imageUrl,
                                                String request_id) {
        switch (resp.code()) {
            case 429:
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
            case 401:
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "models do not exist");
            case 404:
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "404");
            case 500:
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL SERVER ERROR");
            default:
                String token = refresh_token;
                if (!refresh_token.startsWith("eyJhb")) {
                    token = tokenService.getAccessToken(refresh_token);
                    if (token == null) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh_token is wrong");
                    }
                    refreshTokenList.put(refresh_token, token);
                    log.info("assess_token过期，refreshTokenList重置化成功！");
                }
                againImageConversation(response, conversation, token, imageUrl, request_id);
        }
    }


    /**
     * /v1/images/generations
     * 重新回复问题
     */
    public Object againImageConversation(HttpServletResponse response,
                                         @org.springframework.web.bind.annotation.RequestBody Image conversation,
                                         String access_token,
                                         String imageUrl,
                                         String request_id) {
        Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
        try {
            if (!imageUrl.contains("oaifree")) {
                String json = JSON.toJSONString(conversation);
                // 创建一个 RequestBody 对象
                RequestBody requestBody = RequestBody.create(json, mediaType);
                Request streamRequest = new Request.Builder()
                        .url(imageUrl)
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer " + access_token)
                        .addHeader("Content-Type", "application/json")
                        .build();
                try (Response resp = client.newCall(streamRequest).execute()) {
                    if (!resp.isSuccessful()) {
                        return new ResponseEntity<>(Result.error("refresh_token is wrong Or your network is wrong"), HttpStatus.UNAUTHORIZED);
                    } else {
                        // 回复image回答
                        outPutService.outPutImage(response, resp, conversation);
                    }
                }
            } else {
                String json = "{\n" +
                        "  \"model\": \"" + (image_mobel != null ? image_mobel : "gpt-4") + "\",\n" +
                        "  \"stream\": false,\n" +
                        "  \"messages\": [\n" +
                        "    {\n" +
                        "      \"content\": \"" + conversation.getPrompt() + "\",\n" +
                        "      \"role\": \"user\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}";
                RequestBody requestBody = RequestBody.create(json, mediaType);
                // 去除指定部分
                log.info("请求oaifree image回复接口：" + imageUrl);
                Request.Builder requestBuilder = new Request.Builder().url(imageUrl).post(requestBody);
                headersMap.forEach(requestBuilder::addHeader);
                Request streamRequest = requestBuilder.build();
                try (Response resp = client.newCall(streamRequest).execute()) {
                    if (resp.isSuccessful()) {
                        String respStr = resp.body().string();
                        JSONObject jsonObject = new JSONObject(respStr);
                        String created = jsonObject.getString("created");
                        JSONArray choicesArray = jsonObject.getJSONArray("choices");
                        if (choicesArray.length() > 0) {
                            JSONObject firstChoice = choicesArray.getJSONObject(0);
                            JSONObject messageObject = firstChoice.getJSONObject("message");
                            String content = messageObject.getString("content");
                            Matcher matcher = pattern.matcher(content);
                            if (matcher.find()) {
                                String urlAndText = matcher.group(1);
                                String[] splitArray = urlAndText.split(" ", 2);
                                if (splitArray.length == 2) {
                                    String url = splitArray[0].trim();
                                    String reply = "```\n{ " + splitArray[1].trim() + "}\n```";
                                    JSONObject dataObject = new JSONObject();
                                    dataObject.put("url", url);
                                    JSONObject newJson = new JSONObject();
                                    newJson.put("created", created);
                                    newJson.put("data", dataObject);
                                    newJson.put("reply", reply);
                                    outPutService.outPutOaifreeImage(response, newJson, conversation);
                                }
                            }
                        } else {
                            return new ResponseEntity<>(Result.error("INTERNAL SERVER ERROR"), HttpStatus.INTERNAL_SERVER_ERROR);
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * /v1/audio/speech
     * 如发现token过期
     * 重新回复问题
     */
    public void speechManageUnsuccessfulResponse(ConcurrentHashMap<String, String> refreshTokenList,
                                                 Response resp,
                                                 String refresh_token,
                                                 HttpServletResponse response,
                                                 @org.springframework.web.bind.annotation.RequestBody Speech conversation,
                                                 String speechUrl,
                                                 String request_id) {
        switch (resp.code()) {
            case 429:
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
            case 401:
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "models do not exist");
            case 404:
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "404");
            case 500:
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL SERVER ERROR");
            default:
                String token = refresh_token;
                if (!refresh_token.startsWith("eyJhb")) {
                    token = tokenService.getAccessToken(refresh_token);
                    if (token == null) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh_token is wrong");
                    }
                    refreshTokenList.put(refresh_token, token);
                    log.info("assess_token过期，refreshTokenList重置化成功！");
                }
                againSpeechConversation(response, conversation, token, speechUrl, request_id);
        }
    }

    /**
     * /v1/audio/speech
     * 重新回复问题
     */
    public Object againSpeechConversation(HttpServletResponse response,
                                          @org.springframework.web.bind.annotation.RequestBody Speech conversation,
                                          String access_token,
                                          String speechUrl,
                                          String request_id) {
        try {
            Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
            String json = com.alibaba.fastjson2.JSON.toJSONString(conversation);
            RequestBody requestBody = RequestBody.create(json, mediaType);
            log.info("请求speech回复接口：" + speechUrl);
            Request.Builder requestBuilder = new Request.Builder().url(speechUrl).post(requestBody);
            headersMap.forEach(requestBuilder::addHeader);
            Request streamRequest = requestBuilder.build();
            try (Response resp = client.newCall(streamRequest).execute()) {
                if (!resp.isSuccessful()) {
                    return new ResponseEntity<>("refresh_token is wrong Or your network is wrong", HttpStatus.UNAUTHORIZED);
                } else {
                    // speech 输出
                    outPutService.outPutSpeech(response, resp, conversation);
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * v1/audio/transcriptions
     * 如发现token过期
     * 重新回复问题
     */
    public void audioManageUnsuccessfulResponse(ConcurrentHashMap<String, String> refreshTokenList,
                                                Response resp,
                                                String refresh_token,
                                                HttpServletResponse response,
                                                RequestBody fileBody, String filename,
                                                String model,
                                                String audioUrl, String request_id) {
        switch (resp.code()) {
            case 429:
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
            case 401:
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "models do not exist");
            case 404:
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "404");
            case 500:
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL SERVER ERROR");
            default:
                String token = refresh_token;
                if (!refresh_token.startsWith("eyJhb")) {
                    token = tokenService.getAccessToken(refresh_token);
                    if (token == null) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh_token is wrong");
                    }
                    refreshTokenList.put(refresh_token, token);
                    log.info("assess_token过期，refreshTokenList重置化成功！");
                }
                againAudioConversation(response, fileBody, model, filename, token, audioUrl, request_id);
        }
    }

    /**
     * v1/audio/transcriptions
     * 重新回复问题
     */
    public Object againAudioConversation(HttpServletResponse response,
                                         RequestBody fileBody,
                                         String model,
                                         String filename,
                                         String access_token,
                                         String audioUrl,
                                         String request_id) {
        try {
            Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("file", filename, fileBody)
                    .build();
            Request.Builder requestBuilder = new Request.Builder()
                    .url(audioUrl)
                    .post(body);

            headersMap.forEach(requestBuilder::addHeader);
            try (Response resp = client.newCall(requestBuilder.build()).execute()) {
                if (!resp.isSuccessful()) {
                    return new ResponseEntity<>("refresh_token is wrong Or your network is wrong", HttpStatus.UNAUTHORIZED);
                } else {
                    // Audio 输出
                    outPutService.outPutAudio(response, resp, model);
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}