package co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaTenantRepository extends JpaRepository<TenantEntity, UUID> {

  List<TenantEntity> findAllByOrderByCreatedAtAsc();
}
