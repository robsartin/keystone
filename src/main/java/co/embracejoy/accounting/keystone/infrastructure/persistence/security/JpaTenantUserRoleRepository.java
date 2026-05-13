package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

interface JpaTenantUserRoleRepository
    extends JpaRepository<TenantUserRoleEntity, TenantUserRoleEntity.Key> {

  Optional<TenantUserRoleEntity> findByTenantIdAndUserSub(UUID tenantId, String userSub);

  List<TenantUserRoleEntity> findAllByTenantIdOrderByGrantedAtAsc(UUID tenantId);

  long countByTenantIdAndRole(UUID tenantId, String role);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  long deleteByTenantIdAndUserSub(UUID tenantId, String userSub);
}
