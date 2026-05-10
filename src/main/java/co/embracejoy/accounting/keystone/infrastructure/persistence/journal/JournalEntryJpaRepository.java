package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JournalEntryJpaRepository extends JpaRepository<JournalEntryEntity, UUID> {}
