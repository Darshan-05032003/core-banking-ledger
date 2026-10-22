package com.darshan.ledger.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

// Resolves the RSA key pair that signs and verifies access tokens. Production must provide the
// keys; without them the application refuses to start rather than silently minting tokens nobody
// else can verify.
public final class JwtKeySource {

    private static final Logger log = LoggerFactory.getLogger(JwtKeySource.class);
    private static final String PEM_MARKER = "-----BEGIN";
    private static final int GENERATED_KEY_BITS = 2048;

    private JwtKeySource() {
    }

    public static RSAKey resolve(SecurityProperties.Jwt jwt) {
        if (jwt.hasConfiguredKeys()) {
            return toJwk(loadPrivateKey(jwt.privateKey()), loadPublicKey(jwt.publicKey()));
        }
        if (!jwt.allowGeneratedKeys()) {
            throw new IllegalStateException("No JWT signing key configured. Set APP_SECURITY_JWT_PRIVATE_KEY and "
                    + "APP_SECURITY_JWT_PUBLIC_KEY to PEM text or to a file: location");
        }
        KeyPair generated = generate();
        log.warn("Generated an ephemeral RSA key pair for JWT signing; tokens will not survive a restart");
        return toJwk((RSAPrivateKey) generated.getPrivate(), (RSAPublicKey) generated.getPublic());
    }

    static RSAPrivateKey loadPrivateKey(String value) {
        try (InputStream in = open(value)) {
            return RsaKeyConverters.pkcs8().convert(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the JWT private key", ex);
        }
    }

    static RSAPublicKey loadPublicKey(String value) {
        try (InputStream in = open(value)) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the JWT public key", ex);
        }
    }

    // Environment variables often carry PEM text with the line breaks written as the two
    // characters backslash and n; they are turned back into real line breaks here.
    private static InputStream open(String value) throws IOException {
        String trimmed = value.trim();
        if (trimmed.startsWith(PEM_MARKER)) {
            String pem = trimmed.replace("\\n", "\n");
            return new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII));
        }
        return locate(trimmed).getInputStream();
    }

    private static Resource locate(String location) {
        if (location.contains(":")) {
            ResourceLoader loader = new DefaultResourceLoader();
            return loader.getResource(location);
        }
        return new FileSystemResource(location);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA is not available in this JVM", ex);
        }
    }

    private static RSAKey toJwk(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not derive a key id from the JWT public key", ex);
        }
    }
}
