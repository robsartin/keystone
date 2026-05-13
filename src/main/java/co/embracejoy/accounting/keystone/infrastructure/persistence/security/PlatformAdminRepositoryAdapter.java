package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdmin;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link PlatformAdminRepository}.
 *
 * <p>The {@code platform_admins} table is intentionally NOT RLS-protected — platform admins cross
 * the tenant boundary by definition. Phase D moves this adapter to a dedicated platform-pool {@code
 * DataSource}; for B-infra it uses the default app pool.
 */
@Repository
@Transactional
public class PlatformAdminRepositoryAdapter implements PlatformAdminRepository {

  private final JpaPlatformAdminRepository jpa;

  public PlatformAdminRepositoryAdapter(JpaPlatformAdminRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public PlatformAdmin grant(String userSub) {
    Optional<PlatformAdminEntity> existing = jpa.findById(userSub);
    if (existing.isPresent()) {
      return PlatformAdminEntityMapper.toDomain(existing.get());
    }
    PlatformAdminEntity saved = jpa.save(new PlatformAdminEntity(userSub, Instant.now()));
    return PlatformAdminEntityMapper.toDomain(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlatformAdmin> findBySub(String userSub) {
    return jpa.findById(userSub).map(PlatformAdminEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean exists(String userSub) {
    return jpa.existsById(userSub);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlatformAdmin> findAll() {
    return jpa.findAllByOrderByGrantedAtAsc().stream()
        .map(PlatformAdminEntityMapper::toDomain)
        .toList();
  }
}
