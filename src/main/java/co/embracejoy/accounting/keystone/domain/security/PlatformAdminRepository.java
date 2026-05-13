package co.embracejoy.accounting.keystone.domain.security;

import java.util.List;
import java.util.Optional;

/** Persistence port for {@link PlatformAdmin}. */
public interface PlatformAdminRepository {

  /** Insert a platform admin. Idempotent: re-grant returns the existing row. */
  PlatformAdmin grant(String userSub);

  /** Look up by sub. */
  Optional<PlatformAdmin> findBySub(String userSub);

  /** True iff the user is a platform admin. Cheap existence check used per request. */
  boolean exists(String userSub);

  /** All platform admins, ordered by grantedAt ASC. */
  List<PlatformAdmin> findAll();
}
