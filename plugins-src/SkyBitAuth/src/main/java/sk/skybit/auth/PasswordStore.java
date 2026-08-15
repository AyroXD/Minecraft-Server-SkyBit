package sk.skybit.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordStore {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final Properties accounts = new Properties();

    PasswordStore(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        file = dataDirectory.resolve("accounts.properties");
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                accounts.load(input);
            }
        }
    }

    synchronized boolean exists(String username) {
        return accounts.containsKey(key(username));
    }

    synchronized boolean create(String username, String password) throws Exception {
        String key = key(username);
        if (accounts.containsKey(key)) return false;
        accounts.setProperty(key, encode(password));
        save();
        return true;
    }

    synchronized boolean verify(String username, String password) {
        String encoded = accounts.getProperty(key(username));
        return encoded != null && verifyEncoded(password, encoded);
    }

    synchronized boolean change(String username, String oldPassword, String newPassword) throws Exception {
        String key = key(username);
        String encoded = accounts.getProperty(key);
        if (encoded == null || !verifyEncoded(oldPassword, encoded)) return false;
        accounts.setProperty(key, encode(newPassword));
        save();
        return true;
    }

    private String encode(String password) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$"
            + Base64.getEncoder().encodeToString(salt) + "$"
            + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyEncoded(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private void save() throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            accounts.store(output, "SkyBitAuth accounts - passwords are salted PBKDF2 hashes");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
