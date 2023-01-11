package net.ripe.rpki.ui.audit;

import net.ripe.rpki.commons.util.VersionedId;
import net.ripe.rpki.server.api.commands.*;
import net.ripe.rpki.server.api.dto.CommandAuditData;
import net.ripe.rpki.server.api.dto.ProvisioningAuditData;
import net.ripe.rpki.ui.application.CertificationWicketTestCase;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.wicket.markup.html.basic.MultiLineLabel;
import org.joda.time.DateTime;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static net.ripe.rpki.ui.util.WicketUtils.caIdToPageParameters;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

public class CertificateAuthorityHistoryPageTest extends CertificationWicketTestCase {

    @Test
    public void shouldRenderEmptyPage() {
        when(caViewService.findMostRecentMessagesForCa(isA(UUID.class))).thenReturn(Collections.emptyList());
        when(caViewService.findMostRecentCommandsForCa(PRODUCTION_CA_ID)).thenReturn(Collections.emptyList());

        tester.startPage(CertificateAuthorityHistoryPage.class, caIdToPageParameters(PRODUCTION_CA_ID));
        tester.assertRenderedPage(CertificateAuthorityHistoryPage.class);
    }

    @Test
    public void shouldRenderSummary() {
        when(caViewService.findMostRecentMessagesForCa(isA(UUID.class))).thenReturn(Collections.emptyList());

        List<CommandAuditData> commandList = auditLog(
                new UpdateAllIncomingResourceCertificatesCommand(PRODUCTION_CA_VERSIONED_ID, Integer.MAX_VALUE),
                KeyManagementActivatePendingKeysCommand.manualActivationCommand(PRODUCTION_CA_VERSIONED_ID),
                new KeyManagementInitiateRollCommand(MEMBER_CA_VERSIONED_ID, 0),
                new KeyManagementRevokeOldKeysCommand(MEMBER_CA_VERSIONED_ID),
                new GenerateOfflineCARepublishRequestCommand(PRODUCTION_CA_VERSIONED_ID)
                );

        when(caViewService.findMostRecentCommandsForCa(PRODUCTION_CA_ID)).thenReturn(commandList);
        when(statsCollectorNames.humanizeUserPrincipal("admin")).thenReturn(null);

        tester.startPage(CertificateAuthorityHistoryPage.class, caIdToPageParameters(PRODUCTION_CA_ID));

        tester.assertRenderedPage(CertificateAuthorityHistoryPage.class);
        tester.assertComponent("commandListPanel:commandList:0:summary", MultiLineLabel.class);
    }

    @Test
    public void shouldRenderSummaryWithCommandsAndMessagesOrderedByTime() {
        CommandAuditData command = new CommandAuditData(new DateTime().minusMinutes(20), VersionedId.parse("0"), "admin", "type", CertificateAuthorityCommandGroup.SYSTEM, "just a command", "");
        ProvisioningAuditData provisioningMessage = new ProvisioningAuditData(new DateTime().minusMinutes(10), "principal", "summary");

        List<CommandAuditData> commandList = new ArrayList<>(1);
        commandList.add(command);
        when(caViewService.findMostRecentCommandsForCa(PRODUCTION_CA_ID)).thenReturn(commandList);

        List<ProvisioningAuditData> messageList = new ArrayList<>(1);
        messageList.add(provisioningMessage);
        when(caViewService.findMostRecentMessagesForCa(PRODUCTION_CA_DATA.getUuid())).thenReturn(messageList);

        when(statsCollectorNames.humanizeUserPrincipal(command.getPrincipal())).thenReturn(null);
        when(statsCollectorNames.humanizeUserPrincipal(provisioningMessage.getPrincipal())).thenReturn(null);

        tester.startPage(CertificateAuthorityHistoryPage.class, caIdToPageParameters(PRODUCTION_CA_ID));

        String provisioningMessageSummary = getTextFromPanel("summary", 0);
        assertEquals(provisioningMessage.getSummary(), provisioningMessageSummary);

        String provisioningMessagePrincipal = getTextFromPanel("principal", 0);
        assertEquals(provisioningMessage.getPrincipal(), provisioningMessagePrincipal);

        String provisioningMessageExecutionTime = getTextFromPanel("executionTime", 0);
        String provisioningMessageFormatedExecutionTime = CommandListPanel.executionTimeFormat.print(provisioningMessage.getExecutionTime());
        assertEquals(provisioningMessageFormatedExecutionTime, provisioningMessageExecutionTime);

        String commandSummary = getTextFromPanel("summary", 1);
        assertEquals(command.getSummary(), commandSummary);

        String commandPrincipal = getTextFromPanel("principal", 1);
        assertEquals(command.getPrincipal(), commandPrincipal);

        String commandExecutionTime = getTextFromPanel("executionTime", 1);
        String commandFormatedExecutionTime = CommandListPanel.executionTimeFormat.print(command.getExecutionTime());
        assertEquals(commandFormatedExecutionTime, commandExecutionTime);

    }

    private String getTextFromPanel(String field, int index) {
        return StringEscapeUtils.unescapeHtml(tester.getComponentFromLastRenderedPage("commandListPanel:commandList:"+index+":"+field).getDefaultModelObjectAsString());
    }

    private List<CommandAuditData> auditLog(CertificateAuthorityCommand... commands) {
        List<CommandAuditData> result = new ArrayList<>();
        for (CertificateAuthorityCommand command : commands) {
            result.add(auditEntry(command));
        }
        return result;
    }

    private CommandAuditData auditEntry(CertificateAuthorityCommand command) {
        CommandAuditData commandData = new CommandAuditData(new DateTime().minusMinutes(10), PRODUCTION_CA_VERSIONED_ID, "admin", command.getCommandType(), command.getCommandGroup(), command.getCommandSummary(), "");
        return commandData;
    }
}
