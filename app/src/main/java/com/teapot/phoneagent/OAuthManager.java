package com.teapot.phoneagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 手机本机内置的 OAuth 2.0 + PKCE + RFC 7591 动态客户端注册与 AndroidKeyStore 密钥管理器。
 * 使手机在完全脱离电脑（PC 离线）时，自身即可独立完成与 ChatGPT 云端的 OAuth 2.0 安全配对与鉴权。
 */
final class OAuthManager {
    private static final String PREFS = "bridge_oauth_store";
    private static final String KEY_ENC_PAIRING = "enc_pairing_token";
    private static final String KEY_TOKEN_HASHES = "issued_token_hashes";
    private static final String KEY_REFRESH_HASHES = "issued_refresh_hashes";
    private static final String KEYSTORE_ALIAS = "phone_agent_aes_master";

    private final Context context;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    private static final class AuthCodeRecord {
        final String redirectUri;
        final String codeChallenge;
        final String codeChallengeMethod;
        final long expiresAt;
        AuthCodeRecord(String redirectUri, String codeChallenge, String codeChallengeMethod, long expiresAt) {
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.expiresAt = expiresAt;
        }
    }

    private static final Map<String, AuthCodeRecord> PENDING_CODES = new ConcurrentHashMap<>();
    private static final Map<String, Long> CSRF_NONCES = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_MAP_SIZE = 200;
    private static final int MAX_LOCAL_AUTH_FAILURES = 5;
    private static final long LOCAL_AUTH_LOCK_MS = 15 * 60 * 1000L;
    private static int localAuthFailures = 0;
    private static long localAuthLockedUntil = 0L;

    OAuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 获取或生成由 AndroidKeyStore 硬件加密保护的本机配对令牌。 */
    synchronized String getOrCreatePairingToken() {
        String existingEnc = prefs.getString(KEY_ENC_PAIRING, null);
        if (existingEnc != null) {
            String decrypted = decryptWithKeystore(existingEnc);
            if (decrypted != null && !decrypted.isEmpty()) return decrypted;
            throw new IllegalStateException("AndroidKeyStore 解密本机连接码失败，请在应用中点击「重置连接码并吊销全部授权」重新生成");
        }
        // 仅迁移旧版明文 token 至 AndroidKeyStore 加密存储，迁移后立即清除旧明文项
        SharedPreferences legacy = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE);
        String token = legacy.getString(BridgeService.TOKEN, null);
        if (token == null || token.isEmpty()) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } else {
            legacy.edit().remove(BridgeService.TOKEN).commit();
        }
        String encrypted = encryptWithKeystore(token);
        if (encrypted == null) {
            throw new IllegalStateException("AndroidKeyStore 加密存储本机连接码失败");
        }
        prefs.edit().putString(KEY_ENC_PAIRING, encrypted).commit();
        return token;
    }

    /** 重置本机配对令牌并吊销所有已签发的 OAuth 访问令牌（仅密文存储）。 */
    synchronized String rotatePairingTokenAndRevokeAll() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
                .edit().remove(BridgeService.TOKEN).commit();
        String encrypted = encryptWithKeystore(token);
        if (encrypted == null) {
            throw new IllegalStateException("AndroidKeyStore 加密存储本机连接码失败");
        }
        prefs.edit()
                .remove(KEY_TOKEN_HASHES)
                .remove(KEY_REFRESH_HASHES)
                .putString(KEY_ENC_PAIRING, encrypted)
                .commit();
        PENDING_CODES.clear();
        CSRF_NONCES.clear();
        synchronized (OAuthManager.class) {
            localAuthFailures = 0;
            localAuthLockedUntil = 0L;
        }
        return token;
    }

    static boolean isLocalAuthLocked() {
        synchronized (OAuthManager.class) {
            long now = System.currentTimeMillis();
            if (localAuthLockedUntil > now) return true;
            if (localAuthLockedUntil != 0L && localAuthLockedUntil <= now) {
                localAuthLockedUntil = 0L;
                localAuthFailures = 0;
            }
            return false;
        }
    }

    static void recordLocalAuthAttempt(boolean ok) {
        synchronized (OAuthManager.class) {
            if (ok) {
                localAuthFailures = 0;
                localAuthLockedUntil = 0L;
                return;
            }
            localAuthFailures++;
            if (localAuthFailures >= MAX_LOCAL_AUTH_FAILURES) {
                localAuthLockedUntil = System.currentTimeMillis() + LOCAL_AUTH_LOCK_MS;
            }
        }
    }

    synchronized int getActiveOAuthTokenCount() {
        Set<String> hashes = prefs.getStringSet(KEY_TOKEN_HASHES, null);
        return hashes == null ? 0 : hashes.size();
    }

    void revokeLocalOAuthTokens() {
        synchronized (OAuthManager.class) {
            prefs.edit().remove(KEY_TOKEN_HASHES).remove(KEY_REFRESH_HASHES).commit();
            PENDING_CODES.clear();
        }
    }

    /** 校验 Authorization: Bearer <token> 头（支持本机配对令牌或已签发的 OAuth access_token）。 */
    boolean verifyBearerHeader(String authHeader) {
        if (authHeader == null || authHeader.length() < 8) return false;
        if (!authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String presented = authHeader.substring(7).trim();
        if (presented.isEmpty()) return false;
        String pairing = getOrCreatePairingToken();
        if (MessageDigest.isEqual(
                pairing.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        String hash = sha256Base64Url(presented);
        synchronized (OAuthManager.class) {
            Set<String> hashes = prefs.getStringSet(KEY_TOKEN_HASHES, null);
            return validToken(hashes, hash);
        }
    }

    private static Set<String> pruneExpired(Set<String> entries, long now) {
        Set<String> clean = new HashSet<>();
        if (entries == null) return clean;
        for (String entry : entries) {
            int separator = entry.lastIndexOf(':');
            if (separator <= 0) continue;
            try {
                if (Long.parseLong(entry.substring(separator + 1)) > now) {
                    clean.add(entry);
                }
            } catch (NumberFormatException ignored) { }
        }
        return clean;
    }

    private static boolean validToken(Set<String> entries, String hash) {
        if (entries == null) return false;
        long now = System.currentTimeMillis();
        for (String entry : entries) {
            int separator = entry.lastIndexOf(':');
            if (separator <= 0 || !entry.substring(0, separator).equals(hash)) continue;
            try { return Long.parseLong(entry.substring(separator + 1)) > now; }
            catch (NumberFormatException ignored) { return false; }
        }
        return false;
    }

    String createCsrfNonce() {
        long now = System.currentTimeMillis();
        CSRF_NONCES.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        if (CSRF_NONCES.size() >= MAX_PENDING_MAP_SIZE) {
            CSRF_NONCES.clear();
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        String nonce = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        CSRF_NONCES.put(nonce, now + 10 * 60 * 1000L);
        return nonce;
    }

    boolean consumeCsrfNonce(String nonce) {
        if (nonce == null) return false;
        Long exp = CSRF_NONCES.remove(nonce);
        return exp != null && System.currentTimeMillis() <= exp;
    }

    /** 校验 OAuth 回调地址是否属于受信任的远程 MCP 客户端精确白名单。 */
    static boolean isAllowedCallback(String redirectUri) {
        if (redirectUri == null || redirectUri.isEmpty() || redirectUri.contains("\\")) return false;
        return "https://chatgpt.com/connector_platform_oauth_redirect".equals(redirectUri)
                || redirectUri.matches("https://chatgpt\\.com/connector/oauth/[A-Za-z0-9_-]{1,128}")
                || "https://chat.openai.com/aip/plugin-oauth/callback".equals(redirectUri)
                || "https://claude.ai/api/mcp/auth_callback".equals(redirectUri);
    }

    static boolean isValidS256Challenge(String codeChallenge, String codeChallengeMethod) {
        return "S256".equals(codeChallengeMethod)
                && codeChallenge != null
                && codeChallenge.matches("[A-Za-z0-9_-]{43}");
    }

    String issueAuthCode(String redirectUri, String codeChallenge, String codeChallengeMethod) {
        if (!isAllowedCallback(redirectUri))
            throw new SecurityException("invalid redirect_uri");
        if (!isValidS256Challenge(codeChallenge, codeChallengeMethod))
            throw new SecurityException("S256 PKCE required");
        long now = System.currentTimeMillis();
        PENDING_CODES.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAt <= now);
        if (PENDING_CODES.size() >= MAX_PENDING_MAP_SIZE) {
            throw new SecurityException("too many pending authorization codes");
        }
        byte[] b = new byte[24];
        random.nextBytes(b);
        String code = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        PENDING_CODES.put(code, new AuthCodeRecord(
                redirectUri,
                codeChallenge,
                "S256",
                now + 5 * 60 * 1000L
        ));
        return code;
    }

    JSONObject exchangeToken(Map<String, String> params) throws Exception {
      synchronized (OAuthManager.class) {
        long now = System.currentTimeMillis();
        String grantType = params.get("grant_type");
        if ("refresh_token".equals(grantType)) {
            String refresh = params.get("refresh_token");
            if (refresh == null || refresh.isEmpty()) throw new IllegalArgumentException("missing refresh_token");
            Set<String> refreshSet = pruneExpired(prefs.getStringSet(KEY_REFRESH_HASHES, null), now);
            String refreshHash = sha256Base64Url(refresh);
            if (!validToken(refreshSet, refreshHash)) {
                throw new SecurityException("invalid refresh_token");
            }
            refreshSet.removeIf(entry -> entry.startsWith(refreshHash + ":"));
            prefs.edit().putStringSet(KEY_REFRESH_HASHES, refreshSet).commit();
            return mintTokenResponse();
        }
        if (!"authorization_code".equals(grantType)) throw new IllegalArgumentException("unsupported_grant_type");
        String code = params.get("code");
        if (code == null || code.isEmpty()) throw new IllegalArgumentException("missing code");
        AuthCodeRecord record = PENDING_CODES.remove(code);
        if (record == null || now > record.expiresAt) {
            throw new SecurityException("expired or invalid code");
        }
        if (!record.redirectUri.equals(params.get("redirect_uri"))
                || !isValidS256Challenge(record.codeChallenge, record.codeChallengeMethod)) {
            throw new SecurityException("invalid redirect or PKCE");
        }
        String verifier = params.get("code_verifier");
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) {
            throw new SecurityException("invalid PKCE verifier");
        }
        String computed = sha256Base64Url(verifier);
        if (!MessageDigest.isEqual(
                record.codeChallenge.getBytes(StandardCharsets.UTF_8),
                computed.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("PKCE verification failed");
        }
        return mintTokenResponse();
      }
    }

    private JSONObject mintTokenResponse() throws Exception {
        byte[] ab = new byte[28];
        byte[] rb = new byte[28];
        random.nextBytes(ab);
        random.nextBytes(rb);
        String accessToken = "pa_" + Base64.encodeToString(ab, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String refreshToken = "par_" + Base64.encodeToString(rb, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        long now = System.currentTimeMillis();
        Set<String> tokenSet = pruneExpired(prefs.getStringSet(KEY_TOKEN_HASHES, null), now);
        Set<String> refreshSet = pruneExpired(prefs.getStringSet(KEY_REFRESH_HASHES, null), now);
        tokenSet.add(sha256Base64Url(accessToken) + ":" + (now + 30L * 86400000L));
        refreshSet.add(sha256Base64Url(refreshToken) + ":" + (now + 90L * 86400000L));
        prefs.edit()
                .putStringSet(KEY_TOKEN_HASHES, tokenSet)
                .putStringSet(KEY_REFRESH_HASHES, refreshSet)
                .commit();
        return new JSONObject()
                .put("access_token", accessToken)
                .put("token_type", "Bearer")
                .put("expires_in", 86400 * 30)
                .put("refresh_token", refreshToken);
    }

    static JSONObject buildProtectedResourceMeta(String baseUrl) throws Exception {
        return new JSONObject()
                .put("resource", baseUrl + "/mcp")
                .put("authorization_servers", new JSONArray().put(baseUrl))
                .put("bearer_methods_supported", new JSONArray().put("header"));
    }

    static JSONObject buildAuthServerMeta(String baseUrl) throws Exception {
        return new JSONObject()
                .put("issuer", baseUrl)
                .put("authorization_endpoint", baseUrl + "/oauth/authorize")
                .put("token_endpoint", baseUrl + "/oauth/token")
                .put("registration_endpoint", baseUrl + "/oauth/register")
                .put("response_types_supported", new JSONArray().put("code"))
                .put("grant_types_supported", new JSONArray().put("authorization_code").put("refresh_token"))
                .put("code_challenge_methods_supported", new JSONArray().put("S256"))
                .put("token_endpoint_auth_methods_supported", new JSONArray()
                        .put("none")
                        .put("client_secret_post")
                        .put("client_secret_basic"));
    }

    JSONObject registerClient(String rawBody) throws Exception {
        JSONObject req = (rawBody == null || rawBody.trim().isEmpty()) ? new JSONObject() : new JSONObject(rawBody);
        byte[] b = new byte[8];
        random.nextBytes(b);
        String clientId = "pa_client_" + Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new JSONObject()
                .put("client_id", clientId)
                .put("client_name", req.optString("client_name", "ChatGPT MCP Client"))
                .put("redirect_uris", req.optJSONArray("redirect_uris") != null ? req.optJSONArray("redirect_uris") : new JSONArray())
                .put("grant_types", new JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", new JSONArray().put("code"))
                .put("token_endpoint_auth_method", "none");
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    String renderAuthorizeHtml(Map<String, String> queryParams) {
        String csrf = createCsrfNonce();
        String redirectUri = queryParams.get("redirect_uri");
        String state = queryParams.get("state");
        String codeChallenge = queryParams.get("code_challenge");
        String codeChallengeMethod = queryParams.get("code_challenge_method");
        return "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Teapot Phone-Agent · OAuth 授权</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:420px;margin:32px auto;padding:20px;line-height:1.5;background:#f8f9fa;color:#1f1f1f}"
                + ".card{background:#fff;padding:24px;border-radius:12px;box-shadow:0 2px 10px rgba(0,0,0,.08)}"
                + "input,button{width:100%;padding:12px;margin-top:12px;font-size:16px;box-sizing:border-box;border-radius:8px}"
                + "input{border:1px solid #ccc}button{background:#0b57d0;color:#fff;border:0;font-weight:600}</style></head>"
                + "<body><div class='card'><h2>允许远程 MCP 客户端连接 Teapot Phone-Agent？</h2>"
                + "<p>请打开手机上的<b>Teapot Phone-Agent</b>应用，点击「复制连接码」，粘贴到下方以完成安全配对：</p>"
                + "<form method='POST' action='/oauth/authorize'>"
                + "<input type='hidden' name='csrf' value='" + escapeHtml(csrf) + "'/>"
                + "<input type='hidden' name='redirect_uri' value='" + escapeHtml(redirectUri) + "'/>"
                + "<input type='hidden' name='state' value='" + escapeHtml(state) + "'/>"
                + "<input type='hidden' name='code_challenge' value='" + escapeHtml(codeChallenge) + "'/>"
                + "<input type='hidden' name='code_challenge_method' value='" + escapeHtml(codeChallengeMethod) + "'/>"
                + "<input type='password' name='secret' placeholder='在此粘贴本机连接码' required autofocus/>"
                + "<button type='submit'>批准并连接手机</button></form></div></body></html>";
    }

    private static String sha256Base64Url(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEYSTORE_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private String encryptWithKeystore(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private String decryptWithKeystore(String combined) {
        try {
            int idx = combined.indexOf(':');
            if (idx <= 0) return null;
            byte[] iv = Base64.decode(combined.substring(0, idx), Base64.NO_WRAP);
            byte[] ct = Base64.decode(combined.substring(idx + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
