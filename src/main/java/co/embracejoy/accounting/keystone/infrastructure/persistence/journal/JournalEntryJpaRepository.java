package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface JournalEntryJpaRepository extends JpaRepository<JournalEntryEntity, UUID> {

  @Query("SELECT DISTINCT FUNCTION('to_char', e.occurredOn, 'YYYY-MM') FROM JournalEntryEntity e")
  List<String> findDistinctOccurredMonthStrings();
}
