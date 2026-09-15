import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;

public class GenerateLocalRsaKeys {
    private static void writePem(Path path, String label, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        Files.write(path, Arrays.asList(
            "-----BEGIN " + label + "-----",
            body,
            "-----END " + label + "-----",
            ""
        ), StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(".local-secrets").toAbsolutePath();
        Files.createDirectories(directory);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        writePem(directory.resolve("identity-private.pem"),
            "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        writePem(directory.resolve("identity-public.pem"),
            "PUBLIC KEY", keyPair.getPublic().getEncoded());

        System.out.println("RSA keys created in: " + directory);
    }
}
