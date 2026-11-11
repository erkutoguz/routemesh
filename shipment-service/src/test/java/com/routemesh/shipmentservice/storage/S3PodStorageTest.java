package com.routemesh.shipmentservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.routemesh.shipmentservice.config.PodProperties;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/** Gerçek bir S3 uyumlu depoya (compose'taki MinIO ile aynı imaj) karşı gerçek istemci yolunu sınar. */
class S3PodStorageTest {

    private static final String BUCKET = "routemesh-pod";
    private static final String ACCESS_KEY = "minio_root";
    private static final String SECRET_KEY = "minio_root_dev_secret";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "cgr.dev/chainguard/minio@sha256:99d9c9437fc6cefa47ea66eb0fbccfaa521272f5885fbd0520e0334415b767f7"))
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("minio"))
            .withCommand("server", "/data", "--address", ":9100")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(9100)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9100).withStartupTimeout(Duration.ofSeconds(60)));

    private static S3Client client;
    private static S3PodStorage storage;

    @BeforeAll
    static void start() {
        MINIO.start();
        client = new PodStorageConfiguration().podS3Client(properties(ACCESS_KEY, SECRET_KEY));
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3PodStorage(client, BUCKET);
    }

    @AfterAll
    static void stop() {
        client.close();
        MINIO.stop();
    }

    @Test
    void putThenGet_roundTripsTheBytesAndContentType() throws Exception {
        String key = "pod/" + UUID.randomUUID() + "/photo.jpg";
        byte[] content = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

        storage.put(key, content, "image/jpeg");

        try (InputStream stream = storage.get(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
        assertThat(client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build()).contentType())
                .isEqualTo("image/jpeg");
    }

    @Test
    void largeObjectsSurvive() throws Exception {
        String key = "pod/" + UUID.randomUUID() + "/big.png";
        byte[] content = new byte[5 * 1024 * 1024];
        new java.util.Random(7).nextBytes(content);

        storage.put(key, content, "image/png");

        try (InputStream stream = storage.get(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void readingAMissingObject_isReportedAsStorageUnavailable() {
        assertThatThrownBy(() -> storage.get("pod/missing/none.jpg"))
                .isInstanceOfSatisfying(ShipmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.STORAGE_UNAVAILABLE));
    }

    @Test
    void wrongCredentials_areReportedAsStorageUnavailable() {
        try (S3Client wrong = new PodStorageConfiguration().podS3Client(properties(ACCESS_KEY, "not-the-secret"))) {
            S3PodStorage broken = new S3PodStorage(wrong, BUCKET);

            assertThatThrownBy(() -> broken.put("pod/x/y.jpg", new byte[] {1}, "image/jpeg"))
                    .isInstanceOfSatisfying(ShipmentException.class,
                            e -> assertThat(e.getReason()).isEqualTo(Reason.STORAGE_UNAVAILABLE));
        }
    }

    private static PodProperties properties(String accessKey, String secretKey) {
        return new PodProperties(5, new PodProperties.Otp(Duration.ofHours(24), "unused-in-this-test-0123456789"),
                new PodProperties.Storage("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9100), BUCKET,
                        "us-east-1", accessKey, secretKey));
    }
}
