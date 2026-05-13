package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaPlatformAdminRepository extends JpaRepository<PlatformAdminEntity, String> {

  List<PlatformAdminEntity> findAllByOrderByGrantedAtAsc();
}
