package com.routemesh.shipmentservice.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.shipmentservice.config.PodProperties;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.DeliverCommand;
import com.routemesh.shipmentservice.domain.PodFile;
import com.routemesh.shipmentservice.domain.ProofOfDelivery;
import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.repository.PodRepository;
import com.routemesh.shipmentservice.repository.ShipmentRepository;
import com.routemesh.shipmentservice.service.OtpService.Outcome;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;
import com.routemesh.shipmentservice.storage.PodStorage;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Teslimat kanıtı: dosya yükleme/okuma ve {@link #deliver}. {@code deliver}, kanıt kaydını ve {@code DELIVERED}
 * geçişini tek (dar) veritabanı işleminde yapar; 50 m kuralı kayıt eklemenin kendi koşuludur.
 */
@Service
public class ProofOfDeliveryService {

    public static final double MAX_DISTANCE_METERS = 50.0;
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 200;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    public record FileContent(PodFile file, InputStream stream) {
    }

    private final PodRepository pods;
    private final ShipmentRepository shipments;
    private final ShipmentService shipmentService;
    private final OtpService otpService;
    private final PodStorage storage;
    private final PodProperties properties;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;

    public ProofOfDeliveryService(PodRepository pods, ShipmentRepository shipments, ShipmentService shipmentService,
                                  OtpService otpService, PodStorage storage, PodProperties properties,
                                  MeterRegistry meters, TransactionTemplate transactions) {
        this.pods = pods;
        this.shipments = shipments;
        this.shipmentService = shipmentService;
        this.otpService = otpService;
        this.storage = storage;
        this.properties = properties;
        this.meters = meters;
        this.transactions = transactions;
    }

    // outcome: ok | too_far | otp_wrong | otp_locked
    private void countPod(String outcome) {
        meters.counter("routemesh.shipment.pod.verifications", "outcome", outcome).increment();
    }

    // --- dosya yükleme ---

    /**
     * İçerik türü istemcinin bildirdiğine değil baytlara bakılarak belirlenir (JPEG ve PNG).
     * Yalnızca gönderiye atanan kurye, gönderi yoldayken yükleyebilir.
     */
    public PodFile upload(UUID shipmentId, UUID courierId, PodFile.Kind kind, byte[] content) {
        Shipment shipment = shipments.findById(shipmentId)
                .filter(found -> courierId.equals(found.courierId()))
                .orElseThrow(() -> notFound(shipmentId));
        requireOutForDelivery(shipment);
        if (content == null || content.length == 0) {
            throw invalid("file must not be empty");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new ShipmentException(Reason.PAYLOAD_TOO_LARGE, "file must be at most " + MAX_FILE_BYTES + " bytes");
        }
        String contentType = detectContentType(content);
        if (contentType == null) {
            throw new ShipmentException(Reason.UNSUPPORTED_MEDIA, "file must be a JPEG or PNG image");
        }
        if (pods.countFiles(shipmentId) >= properties.maxFilesPerShipment()) {
            throw invalid("at most " + properties.maxFilesPerShipment() + " files can be uploaded for a shipment");
        }

        UUID fileId = UUID.randomUUID();
        String key = "pod/" + shipmentId + "/" + fileId + (contentType.equals("image/png") ? ".png" : ".jpg");
        storage.put(key, content, contentType);
        PodFile file = new PodFile(fileId, shipmentId, kind, key, sha256(content), content.length, contentType,
                courierId, null);
        pods.insertFile(file);
        return pods.findFile(fileId).orElseThrow();
    }

    static String detectContentType(byte[] content) {
        if (startsWith(content, JPEG)) {
            return "image/jpeg";
        }
        if (startsWith(content, PNG)) {
            return "image/png";
        }
        return null;
    }

    // --- teslimat ---

    /**
     * Dış işlem açmaz: OTP doğrulaması kendi işleminde ({@code REQUIRES_NEW}) bağlantı ister; çağıran bir bağlantıyı
     * tutarken ikincisini beklerse eşzamanlı teslimatlar bağlantı havuzunu kilitler. Bu yüzden önce kilitsiz ön
     * kontroller ve OTP, sonra yalnızca dar bir işlemde kilit + kanıt kaydı + geçiş yapılır.
     */
    public ShipmentResult deliver(DeliverCommand command) {
        Shipment shipment = shipments.findById(command.shipmentId())
                .filter(found -> command.courierId().equals(found.courierId()))
                .orElseThrow(() -> notFound(command.shipmentId()));

        boolean otpVerified = false;
        // Aynı anahtarla tekrar: ön kontroller ve OTP atlanır, özgün sonuç kilitli blokta döner
        if (shipments.findHistoryRequestHash(command.shipmentId(), command.transitionKey()).isEmpty()) {
            requireOutForDelivery(shipment);
            validate(command);
            requireFile(command.photoFileId(), command.shipmentId(), PodFile.Kind.PHOTO, "photo_file_id");
            if (command.signatureFileId() != null) {
                requireFile(command.signatureFileId(), command.shipmentId(), PodFile.Kind.SIGNATURE,
                        "signature_file_id");
            }

            // Yan etkisiz ön kontrol: uzaktaki kurye OTP deneme hakkı harcamasın
            double distance = pods.distanceToDelivery(command.shipmentId(), command.latitude(), command.longitude())
                    .orElseThrow(() -> notFound(command.shipmentId()));
            if (distance > MAX_DISTANCE_METERS) {
                countPod("too_far");
                throw new PodTooFarException(round(distance));
            }
            otpVerified = checkOtp(command);
        }

        boolean verified = otpVerified;
        return transactions.execute(status -> recordAndDeliver(command, verified));
    }

    /** @return OTP doğrulandıysa true, gönderi için OTP istenmiyorsa false */
    private boolean checkOtp(DeliverCommand command) {
        Outcome otp = otpService.verify(command.shipmentId(), command.otp());
        switch (otp) {
            case MISSING -> {
                countPod("otp_wrong");
                throw new ShipmentException(Reason.OTP_INVALID, "recipient OTP is required");
            }
            case INVALID -> {
                countPod("otp_wrong");
                throw new ShipmentException(Reason.OTP_INVALID, "recipient OTP is not valid");
            }
            case LOCKED -> {
                countPod("otp_locked");
                throw new ShipmentException(Reason.OTP_LOCKED,
                        "recipient OTP is locked after too many attempts, a new one must be issued");
            }
            case EXPIRED -> {
                countPod("otp_locked");
                throw new ShipmentException(Reason.OTP_LOCKED,
                        "recipient OTP has expired, a new one must be issued");
            }
            case NOT_REQUIRED, VERIFIED -> { }
        }
        return otp == Outcome.VERIFIED;
    }

    /** Dar işlem: satır kilidi, kanıt kaydı ve {@code DELIVERED} geçişi birlikte. Burada ikinci bağlantı açılmaz. */
    private ShipmentResult recordAndDeliver(DeliverCommand command, boolean otpVerified) {
        Shipment shipment = shipments.findByIdForUpdate(command.shipmentId())
                .filter(found -> command.courierId().equals(found.courierId()))
                .orElseThrow(() -> notFound(command.shipmentId()));
        Predicate<Shipment> access = found -> command.courierId().equals(found.courierId());
        TransitionCommand transition = new TransitionCommand(command.shipmentId(), ShipmentStatus.DELIVERED,
                Actor.COURIER, command.transitionKey(), null, null, ShipmentStatus.OUT_FOR_DELIVERY);

        if (shipments.findHistoryRequestHash(command.shipmentId(), command.transitionKey()).isPresent()) {
            // Aynı anahtar: yalnızca aynı kanıtla tekrarlanırsa özgün sonuç döner
            ProofOfDelivery recorded = pods.findProof(command.shipmentId()).orElse(null);
            if (recorded == null || !sameProof(recorded, command)) {
                throw new ShipmentException(Reason.IDEMPOTENCY_CONFLICT,
                        "transition_key was already used with a different request");
            }
            return shipmentService.transition(transition, access);
        }

        // Kilit altında yeniden doğrula: ön kontrolden sonra eşzamanlı bir istek gönderiyi ilerletmiş olabilir
        requireOutForDelivery(shipment);
        PodFile photo = requireFile(command.photoFileId(), command.shipmentId(), PodFile.Kind.PHOTO, "photo_file_id");

        int inserted = pods.insertProofWithinDistance(command.shipmentId(), command.courierId(), command.latitude(),
                command.longitude(), command.accuracyMeters(), command.recipientName().strip(), photo.id(),
                command.signatureFileId(), otpVerified, MAX_DISTANCE_METERS);
        if (inserted != 1) {
            countPod("too_far");
            throw new PodTooFarException(round(pods.distanceToDelivery(command.shipmentId(), command.latitude(),
                    command.longitude()).orElse(MAX_DISTANCE_METERS)));
        }
        ShipmentResult delivered = shipmentService.transition(transition, access);
        countPod("ok");
        return delivered;
    }

    // --- okuma ---

    @Transactional(readOnly = true)
    public ProofOfDelivery getProof(UUID shipmentId, Predicate<Shipment> access) {
        shipments.findById(shipmentId).filter(access).orElseThrow(() -> notFound(shipmentId));
        return pods.findProof(shipmentId)
                .orElseThrow(() -> new ShipmentException(Reason.NOT_FOUND,
                        "no proof of delivery recorded for shipment " + shipmentId));
    }

    public PodFile getFileMetadata(UUID shipmentId, UUID fileId) {
        return pods.findFile(fileId).filter(file -> file.shipmentId().equals(shipmentId))
                .orElseThrow(() -> new ShipmentException(Reason.NOT_FOUND, "file " + fileId + " does not exist"));
    }

    /**
     * Atanan kurye gönderisinin yüklediği her dosyayı görür; satıcı ve OPS yalnızca teslimat kanıtına bağlanmış
     * dosyaları (yarım kalmış yüklemeler görünmez).
     */
    public FileContent openFile(UUID shipmentId, UUID fileId, Actor actor, Predicate<Shipment> access) {
        shipments.findById(shipmentId).filter(access).orElseThrow(() -> notFound(shipmentId));
        PodFile file = getFileMetadata(shipmentId, fileId);
        if (actor != Actor.COURIER && !pods.findProofFileIds(shipmentId).contains(fileId)) {
            throw new ShipmentException(Reason.NOT_FOUND, "file " + fileId + " does not exist");
        }
        return new FileContent(file, storage.get(file.objectKey()));
    }

    // --- yardımcılar ---

    private PodFile requireFile(UUID fileId, UUID shipmentId, PodFile.Kind kind, String field) {
        if (fileId == null) {
            throw invalid(field + " is required");
        }
        return pods.findFile(fileId)
                .filter(file -> file.shipmentId().equals(shipmentId) && file.kind() == kind)
                .orElseThrow(() -> invalid(field + " must reference an uploaded " + kind + " file of this shipment"));
    }

    private static void requireOutForDelivery(Shipment shipment) {
        if (shipment.status() != ShipmentStatus.OUT_FOR_DELIVERY) {
            throw new ShipmentException(Reason.ILLEGAL_TRANSITION,
                    "shipment is " + shipment.status() + ", expected OUT_FOR_DELIVERY");
        }
    }

    private static void validate(DeliverCommand command) {
        if (!(command.latitude() >= -90 && command.latitude() <= 90)) {
            throw invalid("latitude must be between -90 and 90");
        }
        if (!(command.longitude() >= -180 && command.longitude() <= 180)) {
            throw invalid("longitude must be between -180 and 180");
        }
        if (command.accuracyMeters() != null && !(command.accuracyMeters() >= 0)) {
            throw invalid("accuracy_meters must not be negative");
        }
        String name = command.recipientName();
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH) {
            throw invalid("recipient_name must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (command.otp() != null && command.otp().length() > 32) {
            throw invalid("otp is too long");
        }
    }

    private static boolean sameProof(ProofOfDelivery recorded, DeliverCommand command) {
        return recorded.courierId().equals(command.courierId())
                && recorded.photoFileId().equals(command.photoFileId())
                && Objects.equals(recorded.signatureFileId(), command.signatureFileId())
                && recorded.deliveredLatitude() == command.latitude()
                && recorded.deliveredLongitude() == command.longitude()
                && command.recipientName() != null
                && recorded.recipientName().equals(command.recipientName().strip());
    }

    private static double round(double meters) {
        return Math.round(meters * 10) / 10.0;
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ShipmentException notFound(UUID shipmentId) {
        return new ShipmentException(Reason.NOT_FOUND, "shipment " + shipmentId + " does not exist");
    }

    private static ShipmentException invalid(String message) {
        return new ShipmentException(Reason.INVALID_REQUEST, message);
    }
}
