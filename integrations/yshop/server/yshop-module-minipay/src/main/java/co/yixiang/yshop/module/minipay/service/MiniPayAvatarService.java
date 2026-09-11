package co.yixiang.yshop.module.minipay.service;

import co.yixiang.yshop.module.infra.api.file.FileApi;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MiniPayAvatarService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final RestClient client = RestClient.create();
    private final FileApi files;
    private final Set<String> allowedHosts;

    public MiniPayAvatarService(
            FileApi files,
            @Value("${yshop.minipay.avatar-allowed-hosts:localhost,127.0.0.1}") String allowedHosts) {
        this.files = files;
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String copy(String source, String subject, long profileVersion) {
        if (source == null || source.isBlank()) return null;
        URI uri = URI.create(source);
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (uri.getUserInfo() != null || !allowedHosts.contains(uri.getHost())
                || !("https".equalsIgnoreCase(uri.getScheme()) || loopbackHttp)) {
            throw new MiniPayProblem("MINIPAY_AVATAR_SOURCE_REJECTED",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        byte[] bytes = client.get().uri(uri).accept(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG,
                        MediaType.parseMediaType("image/webp"))
                .retrieve().body(byte[].class);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES || !isSupportedImage(bytes)) {
            throw new MiniPayProblem("MINIPAY_AVATAR_INVALID",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return files.createFile("minipay/avatars/" + subject + "/" + profileVersion, bytes);
    }

    private static boolean isSupportedImage(byte[] bytes) {
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        boolean webp = bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W'
                && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        return jpeg || png || webp;
    }
}
