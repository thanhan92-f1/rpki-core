package net.ripe.rpki.services.impl.handlers;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import net.ripe.ipresource.IpResourceSet;
import net.ripe.rpki.domain.CertificateAuthorityInvariantViolationException;
import net.ripe.rpki.domain.HostedCertificateAuthority;
import net.ripe.rpki.domain.KeyPairEntity;
import net.ripe.rpki.domain.ResourceCertificateRepository;
import net.ripe.rpki.server.api.commands.CertificateAuthorityActivationCommand;
import net.ripe.rpki.server.api.commands.CertificateAuthorityCommand;
import net.ripe.rpki.server.api.commands.UpdateAllIncomingResourceCertificatesCommand;
import net.ripe.rpki.server.api.services.command.CommandStatus;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.Set;
import java.util.stream.Collectors;

@Handler(order = 1000)
@Slf4j
public class HostedCertificateAuthorityOutgoingResourceCertificatesInvariantHandler implements CertificateAuthorityCommandHandler<CertificateAuthorityCommand> {

    private final EntityManager entityManager;
    private final ResourceCertificateRepository resourceCertificateRepository;
    private final Timer invariantCheckTimer;
    private final Counter invariantViolationCounter;

    @Inject
    public HostedCertificateAuthorityOutgoingResourceCertificatesInvariantHandler(MeterRegistry meterRegistry, EntityManager entityManager, ResourceCertificateRepository resourceCertificateRepository) {
        this.entityManager = entityManager;
        this.resourceCertificateRepository = resourceCertificateRepository;

        this.invariantCheckTimer = Timer.builder("rpkicore.hosted.ca.invariant.check.timer")
            .description("time to check hosted certificate authority invariants")
            .register(meterRegistry);
        this.invariantViolationCounter = Counter.builder("rpkicore.hosted.ca.invariant.violation.counter")
            .description("count of hosted certificate authority invariant violations")
            .register(meterRegistry);
    }

    @Override
    public Class<CertificateAuthorityCommand> commandType() {
        return CertificateAuthorityCommand.class;
    }

    @Override
    public void handle(CertificateAuthorityCommand command, CommandStatus commandStatus) {
        try {
            invariantCheckTimer.record(() -> handleInternal(command));
        } catch (CertificateAuthorityInvariantViolationException invariantFailure) {
            invariantViolationCounter.increment();
            log.warn("invariant check failed after {}: {}", command, invariantFailure.getMessage());
        }
    }

    @VisibleForTesting
    void handleInternal(CertificateAuthorityCommand command) {
        HostedCertificateAuthority ca = entityManager.find(HostedCertificateAuthority.class, command.getCertificateAuthorityId());
        if (ca == null) {
            // CA was deleted or not a hosted CA, so nothing to check
            return;
        }

        checkCertificateAuthorityInvariants(ca);

        if (ca.getParent() != null && (command instanceof CertificateAuthorityActivationCommand || command instanceof UpdateAllIncomingResourceCertificatesCommand)) {
            HostedCertificateAuthority parent = entityManager.find(HostedCertificateAuthority.class, ca.getParent().getId());
            if (parent != null) {
                checkCertificateAuthorityInvariants(parent);
            }
        }
    }

    private void checkCertificateAuthorityInvariants(HostedCertificateAuthority ca) {
        IpResourceSet incomingResources = determineIncomingResources(ca);

        checkOutgoingChildResourcesInvariant(ca, incomingResources);

        if (!ca.isManifestAndCrlCheckNeeded()) {
            // Manifest and CRL are up-to-date, so all outgoing RPKI object certificates should be contained in
            // incoming resources.
            checkOutgoingRpkiObjectResourcesInvariant(ca, incomingResources);
        }
    }

    private IpResourceSet determineIncomingResources(HostedCertificateAuthority ca) {
        Set<IpResourceSet> distinctIncomingResources = ca.getKeyPairs().stream()
            .filter(KeyPairEntity::isPublishable)
            .map(kp -> kp.getCurrentIncomingCertificate().getResources())
            .collect(Collectors.toSet());

        if (distinctIncomingResources.isEmpty()) {
            return new IpResourceSet();
        } else if (distinctIncomingResources.size() == 1) {
            return distinctIncomingResources.iterator().next();
        } else {
            throw new CertificateAuthorityInvariantViolationException(String.format(
                "CA %s: not all incoming certificates have the same resources: %s",
                ca,
                distinctIncomingResources
            ));
        }
    }

    private void checkOutgoingChildResourcesInvariant(HostedCertificateAuthority ca, IpResourceSet incomingResources) {
        IpResourceSet currentOutgoingChildCertificateResources = resourceCertificateRepository.findCurrentOutgoingChildCertificateResources(ca.getName());
        if (!incomingResources.contains(currentOutgoingChildCertificateResources)) {
            IpResourceSet missing = new IpResourceSet(currentOutgoingChildCertificateResources);
            missing.removeAll(incomingResources);
            throw new CertificateAuthorityInvariantViolationException(String.format(
                "CA %s: with current resources %s does not contain issued child resources %s",
                ca,
                incomingResources,
                missing
            ));
        }
    }

    private void checkOutgoingRpkiObjectResourcesInvariant(HostedCertificateAuthority ca, IpResourceSet incomingResources) {
        IpResourceSet currentOutgoingResources = resourceCertificateRepository.findCurrentOutgoingRpkiObjectCertificateResources(ca.getName());
        if (!incomingResources.contains(currentOutgoingResources)) {
            IpResourceSet missing = new IpResourceSet(currentOutgoingResources);
            missing.removeAll(incomingResources);
            throw new CertificateAuthorityInvariantViolationException(String.format(
                "CA %s: with current resources %s does not contain issued non-child resources %s",
                ca,
                incomingResources,
                missing
            ));
        }
    }
}
