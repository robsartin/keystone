package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface JpaPeriodRepository extends JpaRepository<PeriodEntity, String> {

  @Query("SELECT p FROM PeriodEntity p WHERE p.status = 'CLOSED' ORDER BY p.yearMonth DESC")
  List<PeriodEntity> findAllClosedDesc();
}
