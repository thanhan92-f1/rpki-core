package net.ripe.rpki.domain;

import net.ripe.ipresource.IpResourceSet;
import net.ripe.rpki.ripencc.support.persistence.Repository;
import net.ripe.rpki.server.api.dto.CaIdentity;
import net.ripe.rpki.server.api.dto.CaStatEvent;
import net.ripe.rpki.server.api.dto.CaStat;

import javax.persistence.LockModeType;
import javax.security.auth.x500.X500Principal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface CertificateAuthorityRepository extends Repository<CertificateAuthority> {

    CertificateAuthority findByName(X500Principal name);

    <T extends CertificateAuthority> T findByTypeAndName(Class<T> type, X500Principal name);

    <T extends CertificateAuthority> T findByTypeAndUuid(Class<T> type, UUID memberUuid, LockModeType lockModeType);

    ProductionCertificateAuthority findRootCAByName(X500Principal name);

    AllResourcesCertificateAuthority findAllresourcesCAByName(X500Principal name);

    Collection<CertificateAuthority> findAllByParent(ParentCertificateAuthority parent);

    HostedCertificateAuthority findHostedCa(Long id);

    NonHostedCertificateAuthority findNonHostedCa(Long id);

    Collection<CaStat> getCAStats();

    Collection<CaStatEvent> getCAStatEvents();

    Map<CaIdentity, IpResourceSet> findAllResourcesByParent(HostedCertificateAuthority parent);

    Collection<HostedCertificateAuthority> findAllWithPendingPublications(LockModeType lockMode);

    int deleteNonHostedPublicKeysWithoutSigningCertificates();
}
