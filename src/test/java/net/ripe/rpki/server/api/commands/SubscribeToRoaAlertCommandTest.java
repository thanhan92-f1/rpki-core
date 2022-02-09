package net.ripe.rpki.server.api.commands;

import net.ripe.rpki.commons.util.VersionedId;
import net.ripe.rpki.commons.validation.roa.RouteValidityState;
import net.ripe.rpki.domain.alerts.RoaAlertFrequency;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class SubscribeToRoaAlertCommandTest {

    private SubscribeToRoaAlertCommand subject;

    @Test
    public void shouldHaveDescriptiveLogEntryForInvalidOnly() {
        subject = new SubscribeToRoaAlertCommand(new VersionedId(1), "bob@example.net", Collections.singletonList(RouteValidityState.INVALID_ASN));
        assertEquals("Subscribed bob@example.net to daily ROA alerts for invalid announcements only.", subject.getCommandSummary());
    }

    @Test
    public void shouldHaveDescriptiveLogEntryForInvalidAndUnknown() {
        subject = new SubscribeToRoaAlertCommand(new VersionedId(1), "bob@example.net", Arrays.asList(RouteValidityState.INVALID_ASN, RouteValidityState.UNKNOWN), RoaAlertFrequency.WEEKLY);
        assertEquals("Subscribed bob@example.net to weekly ROA alerts for invalid and unknown announcements.", subject.getCommandSummary());
    }
}
