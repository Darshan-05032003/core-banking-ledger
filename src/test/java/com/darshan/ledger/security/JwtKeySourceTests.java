package com.darshan.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeySourceTests {

    private static String privatePem;
    private static String publicPem;
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    @Test
    void loadsPemTextWithEscapedLineBreaksAsAnEnvironmentVariableWouldCarryIt() throws Exception {
        RSAKey key = JwtKeySource.resolve(jwt(privatePem.replace("\n", "\\n"), publicPem.replace("\n", "\\n"), false));

        assertThat(key.toRSAPublicKey()).isEqualTo(keyPair.getPublic());
        assertThat(key.toRSAPrivateKey()).isEqualTo(keyPair.getPrivate());
        assertThat(key.getKeyID()).isNotBlank();
    }

    @Test
    void loadsPemFilesFromPlainPathsAndFileLocations(@TempDir Path dir) throws Exception {
        Path privateFile = Files.writeString(dir.resolve("jwt-private.pem"), privatePem);
        Path publicFile = Files.writeString(dir.resolve("jwt-public.pem"), publicPem);

        RSAKey fromPaths = JwtKeySource.resolve(jwt(privateFile.toString(), publicFile.toString(), false));
        RSAKey fromLocations = JwtKeySource.resolve(jwt("file:" + privateFile, "file:" + publicFile, false));

        assertThat(fromPaths.toRSAPublicKey()).isEqualTo(keyPair.getPublic());
        assertThat(fromLocations.toRSAPrivateKey()).isEqualTo(keyPair.getPrivate());
    }

    @Test
    void refusesToStartWithoutKeysUnlessGenerationIsAllowed() {
        assertThatThrownBy(() -> JwtKeySource.resolve(jwt(null, null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SECURITY_JWT_PRIVATE_KEY");

        RSAKey generated = JwtKeySource.resolve(jwt("", "", true));

        assertThat(generated.size()).isEqualTo(2048);
        assertThat(generated.isPrivate()).isTrue();
        assertThat(generated.getKeyID()).isNotBlank();
    }

    @Test
    void everyStartupWithoutConfiguredKeysGetsADifferentPair() throws Exception {
        RSAKey first = JwtKeySource.resolve(jwt(null, null, true));
        RSAKey second = JwtKeySource.resolve(jwt(null, null, true));

        assertThat(first.toRSAPublicKey()).isNotEqualTo(second.toRSAPublicKey());
    }

    @Test
    void aMissingFileIsReportedNotSilentlyReplaced() {
        assertThatThrownBy(() -> JwtKeySource.resolve(jwt("/nowhere/private.pem", "/nowhere/public.pem", true)))
                .hasMessageContaining("private key");
    }

    @Test
    void aMissingPublicKeyFileIsReportedByName() {
        assertThatThrownBy(() -> JwtKeySource.loadPublicKey("file:/nowhere/public.pem"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("public key");
    }

    private static SecurityProperties.Jwt jwt(String privateKey, String publicKey, boolean allowGenerated) {
        return new SecurityProperties.Jwt("core-banking-ledger", Duration.ofMinutes(15), privateKey, publicKey,
                allowGenerated);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
