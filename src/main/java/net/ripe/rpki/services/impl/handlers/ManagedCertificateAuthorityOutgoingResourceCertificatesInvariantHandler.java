package net.ripe.rpki.services.impl.handlers;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import net.ripe.ipresource.ImmutableResourceSet;
import net.ripe.rpki.domain.CertificateAuthorityInvariantViolationException;
import net.ripe.rpki.domain.ManagedCertificateAuthority;
import net.ripe.rpki.domain.KeyPairEntity;
import net.ripe.rpki.domain.ResourceCertificateRepository;
import net.ripe.rpki.server.api.commands.CertificateAuthorityActivationCommand;
import net.ripe.rpki.server.api.commands.CertificateAuthorityCommand;
import net.ripe.rpki.server.api.commands.ChildParentCertificateAuthorityCommand;
import net.ripe.rpki.server.api.commands.ChildSharedParentCertificateAuthorityCommand;
import net.ripe.rpki.server.api.services.command.CommandStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.Set;
import java.util.stream.Collectors;

@ConditionalOnProperty(prefix="certificate.authority.invariant.checking", value="enabled", havingValue = "true")
@Handler(order = 1000)
@Slf4j
public class ManagedCertificateAuthorityOutgoingResourceCertificatesInvariantHandler implements CertificateAuthorityCommandHandler<CertificateAuthorityCommand> {

    private final EntityManager entityManager;
    private final ResourceCertificateRepository resourceCertificateRepository;
    private final Timer invariantCheckTimer;
    private final Counter invariantViolationCounter;

    @Inject
    public ManagedCertificateAuthorityOutgoingResourceCertificatesInvariantHandler(MeterRegistry meterRegistry, EntityManager entityManager, ResourceCertificateRepository resourceCertificateRepository) {
        this.entityManager = entityManager;
        this.resourceCertificateRepository = resourceCertificateRepository;

        this.invariantCheckTimer = Timer.builder("rpkicore.hosted.ca.invariant.check.timer")
            .description("time to check hosted certificate authority invariants")
            .register(meterRegistry);
        this.invariantViolationCounter = Counter.builder("rpkicore.hosted.ca.invariant.violation.counter")
            .description("count of hosted certificate authority invariant violations")
            .register(meterRegistry);

        log.info("Certificate authority invariant checking is enabled");
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
        ManagedCertificateAuthority ca = entityManager.find(ManagedCertificateAuthority.class, command.getCertificateAuthorityId());
        if (ca == null) {
            // CA was deleted or not a managed CA, so nothing to check
            return;
        }

        checkCertificateAuthorityInvariants(ca);

        if (ca.getParent() != null && (
            command instanceof CertificateAuthorityActivationCommand
                || command instanceof ChildParentCertificateAuthorityCommand
                || command instanceof ChildSharedParentCertificateAuthorityCommand
        )) {
            ManagedCertificateAuthority parent = entityManager.find(ManagedCertificateAuthority.class, ca.getParent().getId());
            if (parent != null) {
                checkCertificateAuthorityInvariants(parent);
            }
        }
    }

    private void checkCertificateAuthorityInvariants(ManagedCertificateAuthority ca) {
        ImmutableResourceSet incomingResources = determineIncomingResources(ca);

        // All active certificates must have resources that are contained in the certified resources of this CA.
        checkOutgoingResourceCertificatesInvariant(ca, incomingResources);
    }

    private ImmutableResourceSet determineIncomingResources(ManagedCertificateAuthority ca) {
        Set<ImmutableResourceSet> distinctIncomingResources = ca.getKeyPairs().stream()
            .filter(KeyPairEntity::isPublishable)
            .map(kp -> kp.getCertifiedResources())
            .collect(Collectors.toSet());

        if (distinctIncomingResources.isEmpty()) {
            return ImmutableResourceSet.empty();
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

    private void checkOutgoingResourceCertificatesInvariant(ManagedCertificateAuthority ca, ImmutableResourceSet incomingResources) {
        ImmutableResourceSet currentOutgoingChildCertificateResources = resourceCertificateRepository.findCurrentOutgoingResourceCertificateResources(ca.getName());
        if (!incomingResources.contains(currentOutgoingChildCertificateResources)) {
            throw new CertificateAuthorityInvariantViolationException(String.format(
                "CA %s: with current resources %s does not contain issued resources %s",
                ca,
                incomingResources,
                currentOutgoingChildCertificateResources.difference(incomingResources)
            ));
        }
    }
}
