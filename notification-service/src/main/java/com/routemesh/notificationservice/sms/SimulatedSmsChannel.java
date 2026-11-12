package com.routemesh.notificationservice.sms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.routemesh.notificationservice.config.NotificationProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * SMS simülasyonu: günlüğe yalnızca maskeli numara ve uzunluk yazar. {@code routemesh.notification.sms.sink-file}
 * verilirse ek olarak her mesajı (tam numara ve metin, açık halde) bu dosyaya satır satır JSON yazar: uçtan uca duman
 * testi alıcının OTP'sini buradan okur. Yalnızca geliştirme/test içindir; {@code prod} profilinde ayar verilirse
 * uygulama başlamayı reddeder, çünkü dosya OTP kodlarını açık metin tutar.
 */
@Component
public class SimulatedSmsChannel implements SmsChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSmsChannel.class);
    private static final Profiles PRODUCTION = Profiles.of("prod", "production");

    private final boolean logBody;
    private final Path sinkFile;
    private final JsonMapper jsonMapper;

    public SimulatedSmsChannel(NotificationProperties properties, Environment environment, JsonMapper jsonMapper) {
        this.logBody = properties.sms().logBody();
        this.jsonMapper = jsonMapper;
        String configured = properties.sms().sinkFile();
        if (configured == null || configured.isBlank()) {
            this.sinkFile = null;
            return;
        }
        if (environment.acceptsProfiles(PRODUCTION)) {
            throw new IllegalStateException("routemesh.notification.sms.sink-file must not be set in the prod "
                    + "profile: it writes recipient SMS texts, including delivery OTP codes, to disk in clear text");
        }
        this.sinkFile = Path.of(configured);
        prepare(sinkFile);
        log.warn("SMS sink file enabled at {}: recipient SMS texts (including OTP codes) are written in clear text; "
                + "development and test use only", sinkFile);
    }

    @Override
    public void send(String phoneNumber, String text) {
        if (logBody) {
            log.warn("SMS (simulated) to {} body='{}'", PhoneMasker.mask(phoneNumber), text);
        } else {
            log.info("SMS (simulated) to {} ({} chars)", PhoneMasker.mask(phoneNumber), text.length());
        }
        if (sinkFile != null) {
            append(phoneNumber, text);
        }
    }

    private synchronized void append(String phoneNumber, String text) {
        Map<String, String> line = new LinkedHashMap<>();
        line.put("at", Instant.now().toString());
        line.put("phone", phoneNumber);
        line.put("text", text);
        try {
            Files.writeString(sinkFile, jsonMapper.writeValueAsString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Sessizce yutulmaz: gönderim hatası olarak yukarı çıkar, olay yeniden denenir
            throw new IllegalStateException("could not write the SMS sink file " + sinkFile, e);
        }
    }

    /** Dosyayı yalnızca sahibinin okuyup yazabileceği izinle oluşturur (POSIX dosya sistemlerinde). */
    private static void prepare(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (Files.notExists(file)) {
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
                } else {
                    Files.createFile(file);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not prepare the SMS sink file " + file, e);
        }
    }
}
