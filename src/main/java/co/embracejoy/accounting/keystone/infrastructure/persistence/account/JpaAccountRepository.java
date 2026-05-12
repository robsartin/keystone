package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface JpaAccountRepository extends JpaRepository<AccountEntity, String> {

  List<AccountEntity> findAllByCodeIn(Collection<String> codes);

  boolean existsByParentCode(String parentCode);

  @Transactional
  @Modifying
  @Query(value = "UPDATE accounts SET code = :newCode WHERE code = :existing", nativeQuery = true)
  void renameCode(@Param("existing") String existing, @Param("newCode") String newCode);
}
