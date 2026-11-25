package gecko.rest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Configuration properties for API key authentication.
 *
 * <p>Configure in application.properties:</p>
 * <pre>
 * gecko.api.keys=key1,key2,key3
 * gecko.api.auth-enabled=true
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "gecko.api")
public class ApiKeyProperties {

    /** Set of valid API keys. Empty set = no keys configured (auth effectively bypassed if authEnabled=false). */
    private Set<String> keys = new HashSet<>();

    /** Whether API key authentication is enforced. Default true. Set false to disable for testing. */
    private boolean authEnabled = true;
    private boolean tenantEnabled;
    private java.util.Map<String, String> tenants = new java.util.HashMap<>();
    private java.util.Map<String, SimulationJobLimits.TenantQuota> quota = new java.util.HashMap<>();

    public java.util.Map<String, SimulationJobLimits.TenantQuota> getQuota() { return quota; }
    public void setQuota(java.util.Map<String, SimulationJobLimits.TenantQuota> value) { quota = value; }

    public SimulationJobLimits.TenantQuota quotaFor(String tenant) {
        return quota.getOrDefault(tenant, new SimulationJobLimits.TenantQuota());
    }
    private java.util.Map<String, Role> roles = new java.util.HashMap<>();
    private Role defaultRole = Role.ADMIN;

    public enum Role { ADMIN, USER, READONLY }

    public boolean isTenantEnabled() { return tenantEnabled; }
    public void setTenantEnabled(boolean value) { tenantEnabled = value; }
    public java.util.Map<String, String> getTenants() { return tenants; }
    public void setTenants(java.util.Map<String, String> value) { tenants = value; }

    public String tenantFor(String subject) {
        if (!tenantEnabled) return null;
        String tenant = tenants.get(subject);
        return tenant == null || tenant.isBlank() ? null : tenant;
    }

    @jakarta.annotation.PostConstruct
    void validateTenantCredentials() {
        if (tenantEnabled && (!authEnabled || keys.isEmpty()
                || keys.stream().anyMatch(key -> tenantFor(ApiIdentity.keyId(key)) == null))) {
            throw new IllegalStateException("Tenant mode requires authentication and a tenant for every API key.");
        }
    }

    public java.util.Map<String, Role> getRoles() { return roles; }
    public void setRoles(java.util.Map<String, Role> value) { roles = value; }
    public Role getDefaultRole() { return defaultRole; }
    public void setDefaultRole(Role value) { defaultRole = value; }
    public Role roleFor(String keyId) { return roles.getOrDefault(keyId, defaultRole); }

    public Set<String> getKeys() {
        return keys;
    }

    public void setKeys(Set<String> keys) {
        this.keys = keys;
    }

    // Support comma-separated values from properties
    public void setKeys(String keysStr) {
        this.keys = new HashSet<>();
        if (keysStr != null && !keysStr.isBlank()) {
            for (String k : keysStr.split(",")) {
                String trimmed = k.trim();
                if (!trimmed.isEmpty()) {
                    this.keys.add(trimmed);
                }
            }
        }
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    /**
     * Returns true if the given key is valid.
     *
     * @param key the API key to validate
     * @return true if key is valid, false otherwise
     */
    public boolean isValidKey(String key) {
        if (!authEnabled) {
            return true;
        }
        if (keys.isEmpty()) {
            return true; // No keys configured = open
        }
        return key != null && keys.contains(key);
    }
}
