package net.ripe.rpki.rest.service;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.provisioning.identity.IdentitySerializer;
import net.ripe.rpki.commons.provisioning.identity.PublisherRequest;
import net.ripe.rpki.commons.provisioning.identity.PublisherRequestSerializer;
import net.ripe.rpki.commons.provisioning.identity.RepositoryResponse;
import net.ripe.rpki.commons.provisioning.identity.RepositoryResponseSerializer;
import net.ripe.rpki.domain.NonHostedCertificateAuthority;
import net.ripe.rpki.rest.exception.CaNotFoundException;
import net.ripe.rpki.rest.exception.ObjectNotFoundException;
import net.ripe.rpki.server.api.commands.DeleteNonHostedPublisherCommand;
import net.ripe.rpki.server.api.commands.ProvisionNonHostedPublisherCommand;
import net.ripe.rpki.server.api.dto.NonHostedCertificateAuthorityData;
import net.ripe.rpki.server.api.ports.NonHostedPublisherRepositoryService;
import net.ripe.rpki.server.api.services.command.CertificationResourceLimitExceededException;
import net.ripe.rpki.server.api.services.command.CommandService;
import net.ripe.rpki.server.api.services.read.CertificateAuthorityViewService;
import net.ripe.rpki.server.api.support.objects.CaName;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.EntityNotFoundException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableMap.of;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static net.ripe.rpki.rest.service.AbstractCaRestService.API_URL_PREFIX;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.MediaType.TEXT_XML;

@Slf4j
@Scope("prototype")
@RestController
@RequestMapping(path = API_URL_PREFIX + "/{caName}", produces = {APPLICATION_JSON})
@Tag(name = "/ca/{caName}", description = "Operations on CAs")
public class PublisherRepositoriesService extends AbstractCaRestService {
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    private final CertificateAuthorityViewService certificateAuthorityViewService;
    private final CommandService commandService;
    private final NonHostedPublisherRepositoryService nonHostedPublisherRepositoryService;


    @Autowired
    public PublisherRepositoriesService(CertificateAuthorityViewService certificateAuthorityViewService,
                                        CommandService commandService,
                                        NonHostedPublisherRepositoryService nonHostedPublisherRepositoryService) {
        this.certificateAuthorityViewService = certificateAuthorityViewService;
        this.commandService = commandService;
        this.nonHostedPublisherRepositoryService = nonHostedPublisherRepositoryService;
    }

    @GetMapping(path = "non-hosted/publisher-repositories")
    @Operation(summary = "lists all active publisher repositories for this non-hosted CA")
    public ResponseEntity<?> listNonHostedPublicationRepositories(@PathVariable("caName") final CaName caName) {
        try {
            Map<UUID, RepositoryResponseDto> repositories = certificateAuthorityViewService
                .findNonHostedPublisherRepositories(caName.getPrincipal())
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> RepositoryResponseDto.of(entry.getValue())));

            return ResponseEntity.ok().body(of("repositories", repositories));
        } catch (EntityNotFoundException e) {
            throw new CaNotFoundException(e.getMessage());
        }
    }

    @PostMapping(path = "non-hosted/publisher-repositories", consumes = MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "request a publication point in the non-hosted repository, see RFC8183 section 5.2.3 and 5.2.4")
    public ResponseEntity<?> provisionNonHostedPublicationRepository(
        HttpServletRequest request,
        @PathVariable("caName") final CaName caName,
        @RequestParam("file") MultipartFile file
    ) {
        log.info("Publisher request for non-hosted CA: {}", caName);

        NonHostedCertificateAuthorityData ca = getCa(NonHostedCertificateAuthorityData.class, caName);
        if (certificateAuthorityViewService.findNonHostedPublisherRepositories(ca.getName()).size() >= NonHostedCertificateAuthority.PUBLISHER_REPOSITORIES_LIMIT) {
            return ResponseEntity.status(FORBIDDEN).body(of("error", "maximum number of publisher repositories limit exceeded"));
        }

        /*
         * Generate our own publisher handle instead of using the publisher handle in the request, since we want to
         * determine the handle to use, not the non-hosted CA. Using a random UUID will also avoid any conflicts
         * between different CAs.
         */
        UUID publisherHandle = UUID.randomUUID();
        try {
            final InputStream uploadedInputStream = file.getInputStream();
            final String repositoryRequestBody = IOUtils.toString(uploadedInputStream, CHARSET);
            PublisherRequest publisherRequest = new PublisherRequestSerializer().deserialize(repositoryRequestBody);

            // Core commands must be idempotent (and are automatically retried on transient failures) and this does not
            // work with Krill, since Krill is an external (non-transactional) system and provisioning is not idempotent.
            // So create the repository in Krill before registering the result with core.
            RepositoryResponse repositoryResponse = nonHostedPublisherRepositoryService.provisionPublisher(publisherHandle, publisherRequest);

            Utils.cleanupOnError(
                () -> commandService.execute(new ProvisionNonHostedPublisherCommand(ca.getVersionedId(), publisherHandle, publisherRequest, repositoryResponse)),
                () -> nonHostedPublisherRepositoryService.deletePublisher(publisherHandle)
            );

            return ResponseEntity.created(URI.create(request.getRequestURI() + "/").resolve(publisherHandle.toString()).normalize()).build();
        } catch (EntityNotFoundException e) {
            log.warn("Non-hosted CA was not found for '{}': {}", caName, e.getMessage(), e);
            throw new CaNotFoundException(e.getMessage());
        } catch (CertificationResourceLimitExceededException e) {
            return ResponseEntity.status(FORBIDDEN).body(of("error", e.getMessage()));
        } catch (IOException | IdentitySerializer.IdentitySerializerException | IllegalArgumentException e) {
            log.warn("Could not parse uploaded certificate: {}", e.getMessage(), e);
            return ResponseEntity.status(BAD_REQUEST).body(of("error", e.getMessage()));
        } catch (NonHostedPublisherRepositoryService.DuplicateRepositoryException e) {
            log.warn("Could not create repository: {}", e.getMessage(), e);
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(of("error", e.getMessage()));
        }
    }

    @GetMapping(path = "non-hosted/publisher-repositories/{publisherHandle}/repository-response")
    @Operation(summary = "download the repository response for the provided publisher_handle request, see RFC8183 section 5.2.3 and 5.2.4")
    public ResponseEntity<?> downloadNonHostedPublicationRepositoryResponse(
        @PathVariable("caName") final CaName caName,
        @PathVariable("publisherHandle") UUID publisherHandle
    ) {
        try {
            RepositoryResponse repositoryResponse = certificateAuthorityViewService
                .findNonHostedPublisherRepositories(caName.getPrincipal())
                .get(publisherHandle);
            if (repositoryResponse == null) {
                throw new ObjectNotFoundException("publisher repository not found for handle '" + publisherHandle + "'");
            }

            String filename = "repository-response-" + publisherHandle + ".xml";
            String xml = new RepositoryResponseSerializer().serialize(repositoryResponse);
            return ResponseEntity.ok()
                    .header("content-disposition", "attachment; filename = " + filename)
                    .contentType(TEXT_XML)
                    .body(xml.getBytes(CHARSET));
        } catch (EntityNotFoundException e) {
            throw new CaNotFoundException(e.getMessage());
        }
    }

    @DeleteMapping(path = "non-hosted/publisher-repositories/{publisherHandle}")
    @Operation(summary = "Remove the provided publisher_handle request, see RFC8183 section 5.2.3 and 5.2.4")
    public ResponseEntity<?> deleteNonHostedPublicationRepository(
            @PathVariable("caName") final CaName caName,
            @PathVariable("publisherHandle") UUID publisherHandle
    ) {
        NonHostedCertificateAuthorityData ca = getCa(NonHostedCertificateAuthorityData.class, caName);
        try {
            commandService.execute(new DeleteNonHostedPublisherCommand(ca.getVersionedId(), publisherHandle));
            nonHostedPublisherRepositoryService.deletePublisher(publisherHandle);

            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            // Or just say succeeded since a deletion on a non-existing repository is idempotent?
            throw new ObjectNotFoundException("publisher repository not found for handle '" + publisherHandle + "'");
        }
    }

    @Value
    private static class RepositoryResponseDto {
        String tag;
        String serviceUri;
        String publisherHandle;
        String siaBase;
        String rrdpNotificationUri;
        String repositoryBpkiTa;

        static RepositoryResponseDto of(RepositoryResponse repositoryResponse) {
            return new RepositoryResponseDto(
                repositoryResponse.getTag().orElse(null),
                repositoryResponse.getServiceUri().toASCIIString(),
                repositoryResponse.getPublisherHandle(),
                repositoryResponse.getSiaBase().toASCIIString(),
                repositoryResponse.getRrdpNotificationUri().map(URI::toASCIIString).orElse(null),
                repositoryResponse.getRepositoryBpkiTa().getBase64String()
            );
        }
    }

}
