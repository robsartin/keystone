package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface JpaAccountRepository extends JpaRepository<AccountEntity, AccountEntity.Key> {

  Optional<AccountEntity> findByTenantIdAndCode(UUID tenantId, String code);

  boolean existsByTenantIdAndCode(UUID tenantId, String code);

  List<AccountEntity> findAllByTenantIdOrderByCode(UUID tenantId);

  List<AccountEntity> findAllByTenantIdAndCodeIn(UUID tenantId, Collection<String> codes);

  boolean existsByTenantIdAndParentCode(UUID tenantId, String parentCode);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE accounts SET code = :newCode WHERE tenant_id = :tenantId AND code = :existing",
      nativeQuery = true)
  void renameCodeForTenant(
      @Param("tenantId") UUID tenantId,
      @Param("existing") String existing,
      @Param("newCode") String newCode);
}
