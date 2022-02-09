package net.ripe.rpki.server.api.services.read;

import net.ripe.ipresource.IpResourceSet;
import net.ripe.rpki.server.api.dto.*;

import javax.security.auth.x500.X500Principal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-only service for information related to CertificateAuthorities
 */
public interface CertificateAuthorityViewService {

    /**
     * Finds the certificate authority with the given name.
     *
     * @param name the name of the CA.
     * @return null if no such CA was found, the CA's data otherwise.
     */
    CertificateAuthorityData findCertificateAuthorityByName(X500Principal name);

    CertificateAuthorityData findCertificateAuthority(Long id);

    Long findCertificateAuthorityIdByName(X500Principal name);

    /**
     * Find CA id based on name and type
     */
    Long findCertificateAuthorityIdByTypeAndName(CertificateAuthorityType type, X500Principal name);

    Map<CaIdentity, IpResourceSet> findAllChildrenResourcesForCa(X500Principal productionCaName);

    /**
     * @return non-null collection of hosted CA's
     */
    Collection<CertificateAuthorityData> findAllHostedCertificateAuthorities();

    /**
     * Use this to find all the child CAs of the (1) Production CA in the system.
     */
    Collection<CertificateAuthorityData> findAllChildrenForCa(X500Principal productionCaName);

    Collection<CaIdentity> findAllChildrenIdsForCa(X500Principal productionCaName);

        // Auditing, move to own interface?
    List<CommandAuditData> findMostRecentCommandsForCa(long caId);

    List<ProvisioningAuditData> findMostRecentMessagesForCa(String caUUID);

    Collection<CaStat> getCaStats();

    Collection<? extends CaStatEvent> getCaStatEvents();

    /**
     * @return all subclass instances of {@link net.ripe.rpki.domain.HostedCertificateAuthority HostedCertificateAuthority}
     * that have a pending key, ordered by the depth of the parent CA chain (so the
     * {@link net.ripe.rpki.domain.AllResourcesCertificateAuthority AllResourcesCertificateAuthority} will be first,
     * followed by its immediate children, followed by their immediate children, etc).
     */
    List<CertificateAuthorityData> findAllHostedCertificateAuthoritiesWithPendingKeyPairsOrderedByDepth();
}
