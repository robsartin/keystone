package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaPeriodRepository extends JpaRepository<PeriodEntity, PeriodEntity.Key> {

  Optional<PeriodEntity> findByTenantIdAndYearMonth(UUID tenantId, String yearMonth);

  boolean existsByTenantIdAndYearMonth(UUID tenantId, String yearMonth);

  List<PeriodEntity> findAllByTenantIdAndStatusOrderByYearMonthDesc(UUID tenantId, String status);
}
