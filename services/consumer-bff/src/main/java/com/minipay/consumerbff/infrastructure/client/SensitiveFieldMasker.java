package com.minipay.consumerbff.infrastructure.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts credential material that could otherwise reach a log line or an exception message:
 * payment passwords, SMS codes, OAuth tokens, cookies and full mobile numbers.
 *
 * <p>Redaction is structure aware. A bare {@code "code":"SMS_CODE_INVALID"} business code stays
 * readable while a six-digit SMS code is masked, so diagnostics survive without leaking secrets.
 * Because a naive {@code indexOf('"')} scan desynchronises on the closing quote of a value and then
 * passes every following secret through in clear text, the JSON form is parsed by walking string
 * tokens and deciding key vs. value from what follows the closing quote.
 *
 * <p>Current call sites: none in production code. The BFF relays upstream documents verbatim and
 * never writes them to a log, and every type that carries a credential
 * ({@code ConsumerTokens}, {@code IssuedAuthorization}, {@code OAuthTokenSet}) overrides
 * {@code toString()} to be redacted. This class is the designated helper for the first diagnostic
 * that must include a request or response fragment — for example a redacted upstream error audit
 * once that feature exists — and its tests pin that behaviour before anyone wires it up.
 */
public final class SensitiveFieldMasker {

    /** Keys whose value is always credential material. */
    private static final Set<String> ALWAYS_SECRET_KEYS = Set.of(
            "paypassword",
            "paymentpassword",
            "password",
            "newpassword",
            "oldpassword",
            "codeverifier",
            "code_verifier",
            "access_token",
            "accesstoken",
            "refresh_token",
            "refreshtoken",
            "paymentauthtoken",
            "authorization",
            "cookie",
            "set-cookie");

    /** Keys whose value is a secret only when it looks like an SMS code. */
    private static final Set<String> OTP_KEYS = Set.of("code", "smscode", "sms_code");

    /** Keys whose value is a secret only when it looks like a mobile number or an opaque token. */
    private static final Set<String> MOBILE_KEYS =
            Set.of("mobile", "phone", "rawmobile", "maskedmobile");

    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern OTP = Pattern.compile("^\\d{4,8}$");
    private static final Pattern TOKEN_LIKE = Pattern.compile("^[A-Za-z0-9._\\-]{24,}$");
    private static final Pattern BARE_MOBILE =
            Pattern.compile("(?<![\\d*])(1[3-9]\\d)(\\d{4})(\\d{4})(?![\\d*])");

    private SensitiveFieldMasker() {
    }

    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        return redactBareMobiles(nameValuePairs(maskJsonObjects(message)));
    }

    /** Redacts every {@code {"key":"value"}} object found in the message, including nested ones. */
    private static String maskJsonObjects(String message) {
        StringBuilder out = new StringBuilder(message.length());
        int cursor = 0;
        while (cursor < message.length()) {
            int open = message.indexOf('{', cursor);
            if (open < 0) {
                out.append(message, cursor, message.length());
                break;
            }
            int close = matchingBrace(message, open);
            if (close < 0) {
                out.append(message, cursor, message.length());
                break;
            }
            out.append(message, cursor, open + 1);
            out.append(maskObject(message.substring(open + 1, close)));
            out.append('}');
            cursor = close + 1;
        }
        return out.toString();
    }

    private static int matchingBrace(String message, int open) {
        int depth = 0;
        boolean inString = false;
        for (int index = open; index < message.length(); index++) {
            char current = message.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** Masks the secret values of one JSON object body (the text between its braces). */
    private static String maskObject(String body) {
        List<int[]> tokens = stringTokens(body);
        StringBuilder out = new StringBuilder(body.length());
        int cursor = 0;
        for (int index = 0; index < tokens.size(); index++) {
            int[] token = tokens.get(index);
            String key = body.substring(token[0], token[1]);
            boolean maskable = isCandidateKey(key) && index + 1 < tokens.size();
            int[] value = maskable ? tokens.get(index + 1) : null;
            // The next string token is this key's value only when nothing but a separator sits
            // between the two tokens.
            boolean isValue = maskable
                    && body.substring(token[2], value[0] - 1).matches("\\s*:\\s*")
                    && isSecret(key, body.substring(value[0], value[1]));
            if (!isValue) {
                // Copy this string token verbatim and continue with the next one.
                out.append(body, cursor, token[2]);
                cursor = token[2];
                continue;
            }
            // Copy everything up to the value's opening quote so the separator survives, then
            // replace only the value while keeping it quoted and therefore still JSON.
            out.append(body, cursor, value[0] - 1).append("\"***\"");
            cursor = value[2];
            // Skip the masked value token; its key token was already consumed.
            index++;
        }
        if (cursor < body.length()) {
            out.append(body, cursor, body.length());
        }
        return out.toString();
    }

    /** Finds every string token, returning {contentStart, contentEnd, tokenEnd} for each. */
    private static List<int[]> stringTokens(String body) {
        List<int[]> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < body.length()) {
            int quote = body.indexOf('"', cursor);
            if (quote < 0) {
                break;
            }
            int end = quote + 1;
            while (end < body.length()) {
                char current = body.charAt(end);
                if (current == '\\') {
                    end += 2;
                    continue;
                }
                if (current == '"') {
                    break;
                }
                end++;
            }
            if (end >= body.length()) {
                break;
            }
            tokens.add(new int[] {quote + 1, end, end + 1});
            cursor = end + 1;
        }
        return tokens;
    }

    /** Handles {@code key=value} pairs, used by form-encoded token and revoke requests. */
    private static String nameValuePairs(String message) {
        if (message.indexOf('=') < 0) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length());
        int cursor = 0;
        while (cursor < message.length()) {
            int equals = message.indexOf('=', cursor);
            if (equals < 0) {
                out.append(message, cursor, message.length());
                break;
            }
            int keyStart = cursor;
            int lastSeparator = Math.max(
                    Math.max(message.lastIndexOf(' ', equals), message.lastIndexOf(',', equals)),
                    Math.max(message.lastIndexOf('?', equals), message.lastIndexOf('&', equals)));
            if (lastSeparator >= keyStart) {
                keyStart = lastSeparator + 1;
            }
            String key = message.substring(keyStart, equals);
            int valueEnd = equals + 1;
            while (valueEnd < message.length()) {
                char current = message.charAt(valueEnd);
                if (current == '&' || current == ' ' || current == ',' || current == '}'
                        || current == '\n' || current == ';' || current == '"') {
                    break;
                }
                valueEnd++;
            }
            String value = message.substring(equals + 1, valueEnd);
            out.append(message, cursor, keyStart);
            if (isCandidateKey(key) && isSecret(key, value)) {
                out.append(key).append("=***");
            } else {
                out.append(message, keyStart, valueEnd);
            }
            cursor = valueEnd;
        }
        return out.toString();
    }

    private static boolean isCandidateKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return ALWAYS_SECRET_KEYS.contains(normalized)
                || OTP_KEYS.contains(normalized)
                || MOBILE_KEYS.contains(normalized);
    }

    private static boolean isSecret(String key, String value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (ALWAYS_SECRET_KEYS.contains(normalized)) {
            return !value.isEmpty();
        }
        if (OTP_KEYS.contains(normalized)) {
            return OTP.matcher(value).matches();
        }
        if (MOBILE_KEYS.contains(normalized)) {
            return MOBILE.matcher(value).matches() || TOKEN_LIKE.matcher(value).matches();
        }
        return false;
    }

    /** Backstop for bare mobile numbers that are not inside a JSON document. */
    private static String redactBareMobiles(String message) {
        return BARE_MOBILE.matcher(message).replaceAll("$1****$3");
    }

    /** Masks the credential-bearing headers of an outbound request for diagnostics. */
    public static String redactHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        headers.forEach((name, values) -> {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            boolean secret = ALWAYS_SECRET_KEYS.contains(normalized)
                    || "x-request-id".equals(normalized);
            builder.append(name).append('=').append(secret ? "***" : values);
        });
        return builder.append('}').toString();
    }
}
