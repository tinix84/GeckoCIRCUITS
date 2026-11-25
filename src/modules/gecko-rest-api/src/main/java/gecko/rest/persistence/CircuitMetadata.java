package gecko.rest.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Entity
@Table(name = "circuit_meta")
public class CircuitMetadata {
    @Id
    @Column(columnDefinition = "text")
    private String id;
    @Column(columnDefinition = "text", nullable = false)
    private String name;
    @Column(columnDefinition = "text")
    private String owner;
    @Column(columnDefinition = "text")
    private String tenant;
    @Column(nullable = false)
    private Instant uploadedAt;
    @Column(length = 64, nullable = false)
    private String sha256;
    @Column(nullable = false)
    private byte[] payload;

    protected CircuitMetadata() { }

    public CircuitMetadata(String id, String name, String owner, String tenant, Instant uploadedAt, byte[] payload) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.tenant = tenant;
        this.uploadedAt = uploadedAt;
        replacePayload(payload);
    }

    public String id() { return id; }
    public String name() { return name; }
    public String owner() { return owner; }
    public String tenant() { return tenant; }
    public Instant uploadedAt() { return uploadedAt; }
    public byte[] payload() { return payload.clone(); }

    public void replacePayload(byte[] bytes) {
        try {
            payload = bytes.clone();
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
