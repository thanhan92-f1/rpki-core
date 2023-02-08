package net.ripe.rpki.ui.commons;

import net.ripe.rpki.server.api.configuration.RepositoryConfiguration;
import net.ripe.rpki.server.api.dto.CertificateAuthorityData;
import net.ripe.rpki.server.api.dto.CertificateAuthorityType;
import net.ripe.rpki.server.api.services.read.CertificateAuthorityViewService;
import net.ripe.rpki.ui.admin.UpstreamCaManagementPage;
import net.ripe.rpki.ui.application.CertificationAdminWebSession;
import net.ripe.rpki.ui.application.CertificationAdminWicketApplication;
import net.ripe.rpki.ui.ca.CreateProductionCaPage;
import org.apache.wicket.PageParameters;
import org.apache.wicket.RequestCycle;
import org.apache.wicket.authentication.AuthenticatedWebSession;


public abstract class AdminCertificationBasePage extends MinimalRPKIBasePage {

    private static final long serialVersionUID = 1L;

    protected static final String TITLE_PREFIX = "Resource Certification - ";

    private CertificateAuthorityData currentCertificateAuthority;

    private String pageTitle;

    public AdminCertificationBasePage(PageParameters parameters) {
        this("No title provided", parameters);
    }

    public AdminCertificationBasePage(String pageTitle, PageParameters parameters) {
        this(pageTitle, parameters, true);
    }

    public AdminCertificationBasePage(String pageTitle, PageParameters parameters, boolean interceptNoCA) {
        super(parameters);
        this.pageTitle = pageTitle;

        //if user is not signed in, redirect to sign in page
        CertificationAdminWicketApplication app = CertificationAdminWicketApplication.get();
        if (!AuthenticatedWebSession.get().isSignedIn()) {
            app.onUnauthorizedInstantiation(this);
        }

        if (interceptNoCA) {
            if (!allResourceCaExists()) {
                setResponsePage(UpstreamCaManagementPage.class);
            } else if (!productionCaExists()) {
                setResponsePage(CreateProductionCaPage.class);
            }
        }

        setCurrentCertificateAuthority(getCaViewService().findCertificateAuthorityByName(getRepositoryConfiguration().getProductionCaPrincipal()));
    }

    @Override
    public CertificationAdminWebSession getSession() {
        return CertificationAdminWebSession.get();
    }

    protected CertificateAuthorityData getCurrentCertificateAuthority() {
        return currentCertificateAuthority;
    }

    public void setCurrentCertificateAuthority(CertificateAuthorityData currentCertificateAuthority) {
        this.currentCertificateAuthority = currentCertificateAuthority;
    }

    protected boolean hasCurrentHostedOrRootCertificateAuthority() {
        return hasCurrentCertificateAuthorityOfType(CertificateAuthorityType.HOSTED) || hasCurrentRootCertificateAuthority();
    }

    protected boolean hasCurrentNonHostedCertificateAuthority() {
        return hasCurrentCertificateAuthorityOfType(CertificateAuthorityType.NONHOSTED);
    }

    protected boolean hasCurrentRootCertificateAuthority() {
        return hasCurrentCertificateAuthorityOfType(CertificateAuthorityType.ROOT);
    }

    protected boolean hasCurrentCertificateAuthorityOfAnyType() {
        return currentCertificateAuthority != null;
    }

    private boolean hasCurrentCertificateAuthorityOfType(CertificateAuthorityType type) {
        return hasCurrentCertificateAuthorityOfAnyType() && currentCertificateAuthority.getType() == type;
    }

    private boolean allResourceCaExists() {
        return getCaViewService().findCertificateAuthorityByName(getRepositoryConfiguration().getAllResourcesCaPrincipal()) != null;
    }

    private boolean productionCaExists() {
        return getCaViewService().findCertificateAuthorityByName(getRepositoryConfiguration().getProductionCaPrincipal()) != null;
    }

    @Override
    protected String getPageTitle() {
        return TITLE_PREFIX + pageTitle;
    }

    @Override
    public String pageGAUrl() {
        return RequestCycle.get().getRequest().getPath();
    }

    public CertificateAuthorityViewService getCaViewService() {
        return getBean(CertificateAuthorityViewService.class);
    }

    public RepositoryConfiguration getRepositoryConfiguration() {
        return getBean(RepositoryConfiguration.class);
    }
}
