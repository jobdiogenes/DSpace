/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.google;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections.Buffer;
import org.apache.commons.collections.BufferUtils;
import org.apache.commons.collections.buffer.CircularFifoBuffer;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.google.client.GoogleAnalyticsClient;
import org.dspace.service.ClientInfoService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.model.Event;
import org.dspace.usage.AbstractUsageEventListener;
import org.dspace.usage.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Notifies Google Analytics of Bitstream VIEW events. These events are stored in memory and then
 * asynchronously processed by a single seperate thread.
 *
 * @author April Herron
 * @author Luca Giamminonni
 */
public class GoogleAsyncEventListener extends AbstractUsageEventListener {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAX_TIME_SINCE_EVENT = 14400000;

    // 20 is the event max set by the GA API
    private static final int GA_MAX_EVENTS = 20;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ClientInfoService clientInfoService;

    @Autowired
    private List<GoogleAnalyticsClient> googleAnalyticsClients;

    private Buffer eventsBuffer;

    @PostConstruct
    public void init() {
        int analyticsBufferlimit = configurationService.getIntProperty("google.analytics.buffer.limit", 256);
        eventsBuffer = BufferUtils.synchronizedBuffer(new CircularFifoBuffer(analyticsBufferlimit));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void receiveEvent(Event event) {

        if (!(event instanceof UsageEvent) || isGoogleAnalyticsKeyNotConfigured()) {
            return;
        }

        UsageEvent usageEvent = (UsageEvent) event;
        LOGGER.debug("Usage event received " + event.getName());

        if (isNotBitstreamViewEvent(usageEvent)) {
            return;
        }

        try {
            GoogleAnalyticsEvent analyticsEvent = createGoogleAnalyticsEvent(usageEvent);
            eventsBuffer.add(analyticsEvent);
        } catch (Exception e) {
            logReceiveEventException(usageEvent, e);
        }

    }

    /**
     * Collected events are sent every minute (configurable period).
     */
    public void sendCollectedEvents() {

        if (isGoogleAnalyticsKeyNotConfigured()) {
            return;
        }

        String analyticsKey = getGoogleAnalyticsKey();

        List<GoogleAnalyticsEvent> events = getEventsFromBufferFilteredByEventTime();

        if (events.isEmpty()) {
            return;
        }

        GoogleAnalyticsClient client = getClientByAnalyticsKey(analyticsKey);

        try {
            client.sendEvents(analyticsKey, events);
        } catch (RuntimeException ex) {
            LOGGER.error("An error occurs sending the events.", ex);
        }

    }

    private GoogleAnalyticsEvent createGoogleAnalyticsEvent(UsageEvent usageEvent) {

        HttpServletRequest request = usageEvent.getRequest();

        String clientId = getClientId(usageEvent);
        String referrer = getReferrer(usageEvent);
        String clientIp = clientInfoService.getClientIp(request);
        String userAgent = request.getHeader("USER-AGENT");
        String documentPath = request.getRequestURI() + "?" + request.getQueryString();
        String documentName = getObjectName(usageEvent);

        return new GoogleAnalyticsEvent(clientId, clientIp, userAgent, referrer,
            documentPath, documentName);
    }

    /**
     * Client ID, should uniquely identify the user or device. If we have an
     * X-CORRELATION-ID header or a session ID for the user, then lets use it,
     * othwerwise generate a UUID.
     */
    private String getClientId(UsageEvent usageEvent) {
        if (usageEvent.getRequest().getHeader("X-CORRELATION-ID") != null) {
            return usageEvent.getRequest().getHeader("X-CORRELATION-ID");
        } else if (usageEvent.getRequest().getSession(false) != null) {
            return usageEvent.getRequest().getSession().getId();
        } else {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * Prefer the X-REFERRER header, otherwise fallback to the referrer header.
     */
    private String getReferrer(UsageEvent usageEvent) {
        if (usageEvent.getRequest().getHeader("X-REFERRER") != null) {
            return usageEvent.getRequest().getHeader("X-REFERRER");
        } else {
            return usageEvent.getRequest().getHeader("referer");
        }
    }

    private boolean isNotBitstreamViewEvent(UsageEvent usageEvent) {
        return usageEvent.getAction() != UsageEvent.Action.VIEW
            || usageEvent.getObject().getType() != Constants.BITSTREAM;
    }

    private boolean isGoogleAnalyticsKeyNotConfigured() {
        return StringUtils.isBlank(getGoogleAnalyticsKey());
    }

    private void logReceiveEventException(UsageEvent usageEvent, Exception e) {

        LOGGER.error("Failed to add event to buffer", e);
        LOGGER.error("Event information: " + usageEvent);

        Context context = usageEvent.getContext();
        if (context == null) {
            LOGGER.error("UsageEvent has no Context object");
            return;
        }

        LOGGER.error("Context information:");
        LOGGER.error("    Current User: " + context.getCurrentUser());
        LOGGER.error("    Extra log info: " + context.getExtraLogInfo());
        if (context.getEvents() != null && !context.getEvents().isEmpty()) {
            for (int x = 1; x <= context.getEvents().size(); x++) {
                LOGGER.error("    Context Event " + x + ": " + context.getEvents().get(x));
            }
        }

    }

    private String getObjectName(UsageEvent ue) {
        try {
            if (ue.getObject().getType() == Constants.BITSTREAM) {
                // For a bitstream download we really want to know the title of the owning item
                // rather than the bitstream name.
                return ContentServiceFactory.getInstance().getDSpaceObjectService(ue.getObject())
                        .getParentObject(ue.getContext(), ue.getObject()).getName();
            } else {
                return ue.getObject().getName();
            }
        } catch (SQLException e) {
            // This shouldn't merit interrupting the user's transaction so log the error and continue.
            LOGGER.error("Error in Google Analytics recording - can't determine ParentObjectName for bitstream " +
                    ue.getObject().getID(), e);
        }

        return null;

    }

    private List<GoogleAnalyticsEvent> getEventsFromBufferFilteredByEventTime() {

        List<GoogleAnalyticsEvent> events = new ArrayList<>();

        Iterator<?> iterator = eventsBuffer.iterator();

        while (iterator.hasNext() && events.size() < GA_MAX_EVENTS) {

            GoogleAnalyticsEvent event = (GoogleAnalyticsEvent) iterator.next();
            eventsBuffer.remove(event);

            if ((System.currentTimeMillis() - event.getTime()) < MAX_TIME_SINCE_EVENT) {
                events.add(event);
            }

        }

        return events;
    }

    private GoogleAnalyticsClient getClientByAnalyticsKey(String analyticsKey) {
        return googleAnalyticsClients.stream()
            .filter(client -> client.isAnalyticsKeySupported(analyticsKey))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No Google Analytics Client found for key " + analyticsKey));
    }

    private String getGoogleAnalyticsKey() {
        return configurationService.getProperty("google.analytics.key");
    }
}
