package co.embracejoy.accounting.keystone.testsupport;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints signed test JWTs against a locally-held RSA key pair — no IdP round-trip.
 *
 * <p>{@code TestSecurityConfig} wires a {@code JwtDecoder} that validates against {@link
 * #publicKey()}, so tokens minted here are accepted by the app's real OAuth2 resource-server filter
 * chain in {@code @WebMvcTest} slices and {@code @SpringBootTest}s alike, exercising the production
 * {@code JwtTenantConverter} rather than a test double.
 */
public final class JwtTestSupport {

  private static final RSAKey GENERATED_KEY = generateKey();
  private static final long DEFAULT_TTL_SECONDS = 3600L;

  private final RSAKey key;
  private final String issuer;
  private final String audience;
  private final String tenantClaim;

  public JwtTestSupport(RSAKey key, String issuer, String audience, String tenantClaim) {
    this.key = key;
    this.issuer = issuer;
    this.audience = audience;
    this.tenantClaim = tenantClaim;
  }

  /** The RSA key pair generated once for the whole test JVM. */
  public static RSAKey generatedKey() {
    return GENERATED_KEY;
  }

  /** The public half of this instance's signing key, for a test {@code JwtDecoder} to validate. */
  public RSAKey publicKey() {
    return key.toPublicJWK();
  }

  /** Mint a token with the given sub and tenant, expiring one hour from now. */
  public String mint(String sub, TenantId tenant) {
    return mint(sub, tenant, Instant.now().plusSeconds(DEFAULT_TTL_SECONDS));
  }

  /** Mint a token with a specific expiry (for expiry-testing). */
  public String mint(String sub, TenantId tenant, Instant expiresAt) {
    JWTClaimsSet claims =
        claimsBuilder(sub, expiresAt).claim(tenantClaim, tenant.value().toString()).build();
    return sign(claims);
  }

  /** Mint a token with only a sub (no tenant claim) — for platform-admin-only tokens. */
  public String mintWithoutTenant(String sub) {
    JWTClaimsSet claims =
        claimsBuilder(sub, Instant.now().plusSeconds(DEFAULT_TTL_SECONDS)).build();
    return sign(claims);
  }

  private JWTClaimsSet.Builder claimsBuilder(String sub, Instant expiresAt) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(List.of(audience))
        .subject(sub)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(expiresAt));
  }

  private String sign(JWTClaimsSet claims) {
    try {
      JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build();
      SignedJWT jwt = new SignedJWT(header, claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("failed to sign test JWT", e);
    }
  }

  private static RSAKey generateKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("failed to generate test RSA key", e);
    }
  }
}
