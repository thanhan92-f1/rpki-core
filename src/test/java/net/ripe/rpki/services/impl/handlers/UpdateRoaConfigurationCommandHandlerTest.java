package net.ripe.rpki.services.impl.handlers;

import net.ripe.ipresource.Asn;
import net.ripe.ipresource.IpRange;
import net.ripe.rpki.domain.CertificateAuthorityRepository;
import net.ripe.rpki.domain.HostedCertificateAuthority;
import net.ripe.rpki.domain.TestServices;
import net.ripe.rpki.domain.roa.RoaConfiguration;
import net.ripe.rpki.domain.roa.RoaConfigurationPrefix;
import net.ripe.rpki.domain.roa.RoaConfigurationRepository;
import net.ripe.rpki.domain.roa.RoaEntityService;
import net.ripe.rpki.server.api.commands.UpdateRoaConfigurationCommand;
import net.ripe.rpki.server.api.configuration.RepositoryConfiguration;
import net.ripe.rpki.server.api.dto.RoaConfigurationPrefixData;
import net.ripe.rpki.server.api.services.command.RoaConfigurationForPrivateASNException;
import net.ripe.rpki.services.impl.background.RoaMetricsService;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static net.ripe.rpki.domain.ProductionCertificateAuthorityTest.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class UpdateRoaConfigurationCommandHandlerTest {

    private static final Asn ASN = Asn.parse("1234");
    private static final String PRIVATE_ASNS = "64512-65535, 4200000000-4294967294";

    private static final Asn PRIVATE_ASN = Asn.parse("AS64614");

    private static final IpRange PREFIX = IpRange.parse("10.1/16");

    private static final Long TEST_CA_ID = 2L;

    private HostedCertificateAuthority certificateAuthority;

    private CertificateAuthorityRepository certificateAuthorityRepository;

    private RoaConfigurationRepository roaConfigurationRepository;

    private RoaEntityService roaEntityService;

    private UpdateRoaConfigurationCommandHandler subject;

    private RoaConfiguration configuration;

    private RoaMetricsService roaMetricsService;

    @Before
    public void setUp() {
        certificateAuthority = createInitialisedProdCaWithRipeResources(TestServices.createCertificateManagementService());
        certificateAuthorityRepository = mock(CertificateAuthorityRepository.class);
        roaConfigurationRepository = mock(RoaConfigurationRepository.class);
        roaEntityService = mock(RoaEntityService.class);
        roaMetricsService = mock(RoaMetricsService.class);

        when(certificateAuthorityRepository.findHostedCa(certificateAuthority.getId())).thenReturn(certificateAuthority);

        subject = new UpdateRoaConfigurationCommandHandler(certificateAuthorityRepository, roaConfigurationRepository
                , roaEntityService, PRIVATE_ASNS, roaMetricsService);
        configuration = new RoaConfiguration(certificateAuthority);

        when(roaConfigurationRepository.getOrCreateByCertificateAuthority(certificateAuthority)).thenReturn(configuration);
    }

    @Test
    public void should_add_new_additions() {
        subject.handle(new UpdateRoaConfigurationCommand(
                certificateAuthority.getVersionedId(),
                Collections.singletonList(new RoaConfigurationPrefixData(ASN, PREFIX, null)),
                Collections.emptyList()));

        assertEquals(Collections.singleton(new RoaConfigurationPrefix(ASN, PREFIX, null)), configuration.getPrefixes());
        verify(roaMetricsService).countAdded(1);
    }

    @Test(expected = RoaConfigurationForPrivateASNException.class)
    public void should_reject_new_additions_of_private_ASN() {
        subject.handle(new UpdateRoaConfigurationCommand(
                certificateAuthority.getVersionedId(),
                Collections.singletonList(new RoaConfigurationPrefixData(PRIVATE_ASN, PREFIX, null)),
                Collections.emptyList()));
        verifyNoMoreInteractions(roaMetricsService);
    }

    @Test
    public void should_remove_deletions() {
        configuration.addPrefix(Collections.singleton(new RoaConfigurationPrefix(ASN, PREFIX, null)));

        subject.handle(new UpdateRoaConfigurationCommand(
                certificateAuthority.getVersionedId(),
                Collections.emptyList(),
                Collections.singletonList(new RoaConfigurationPrefixData(ASN, PREFIX, null))));

        assertEquals(Collections.emptySet(), configuration.getPrefixes());
        verify(roaMetricsService).countDeleted(1);
    }

    @Test
    public void should_notify_roa_entity_service_on_configuration_change() {
        subject.handle(new UpdateRoaConfigurationCommand(
                certificateAuthority.getVersionedId(),
                Collections.emptyList(),
                Collections.emptyList()));

        verify(roaEntityService, times(1)).roaConfigurationUpdated(certificateAuthority);
    }
}
