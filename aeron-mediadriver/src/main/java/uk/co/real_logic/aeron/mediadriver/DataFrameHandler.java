/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.mediadriver;

import uk.co.real_logic.aeron.util.AtomicArray;
import uk.co.real_logic.aeron.util.collections.Long2ObjectHashMap;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.protocol.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Frame processing for data
 */
public class DataFrameHandler implements FrameHandler, AutoCloseable
{
    private final UdpTransport transport;
    private final UdpDestination destination;
    private final Long2ObjectHashMap<Subscription> subscriptionByChannelIdMap = new Long2ObjectHashMap<>();
    private final MediaConductorProxy conductorProxy;
    private final AtomicArray<SubscribedSession> globalSubscribedSessions;
    private final ByteBuffer sendSmBuffer = ByteBuffer.allocateDirect(StatusMessageFlyweight.HEADER_LENGTH);
    private final AtomicBuffer writeSmBuffer = new AtomicBuffer(sendSmBuffer);
    private final ByteBuffer sendNakBuffer = ByteBuffer.allocateDirect(128);
    private final AtomicBuffer writeNakBuffer = new AtomicBuffer(sendNakBuffer);
    private final StatusMessageFlyweight statusMessageFlyweight = new StatusMessageFlyweight();
    private final NakFlyweight nakHeader = new NakFlyweight();

    public DataFrameHandler(final UdpDestination destination,
                            final NioSelector nioSelector,
                            final MediaConductorProxy conductorProxy,
                            final AtomicArray<SubscribedSession> globalSubscribedSessions)
        throws Exception
    {
        this.globalSubscribedSessions = globalSubscribedSessions;
        this.transport = new UdpTransport(this, destination, nioSelector);
        this.destination = destination;
        this.conductorProxy = conductorProxy;
    }

    public void close()
    {
        transport.close();
    }

    public UdpDestination destination()
    {
        return destination;
    }

    public Long2ObjectHashMap<Subscription> subscriptionMap()
    {
        return subscriptionByChannelIdMap;
    }

    public void addChannels(final long[] channelIdList)
    {
        for (final long channelId : channelIdList)
        {
            Subscription subscription = subscriptionByChannelIdMap.get(channelId);

            if (null != subscription)
            {
                subscription.incRef();
            }
            else
            {
                subscription = new Subscription(destination, channelId, conductorProxy, globalSubscribedSessions);
                subscriptionByChannelIdMap.put(channelId, subscription);
            }
        }
    }

    public void removeChannels(final long[] channelIds)
    {
        for (final long channelId : channelIds)
        {
            final Subscription subscription = subscriptionByChannelIdMap.get(channelId);

            if (subscription == null)
            {
                throw new SubscriptionNotRegisteredException("No subscription registered on " + channelId);
            }

            if (subscription.decRef() == 0)
            {
                subscriptionByChannelIdMap.remove(channelId);
                subscription.close();
            }
        }
    }

    public int channelCount()
    {
        return subscriptionByChannelIdMap.size();
    }

    public void onDataFrame(final DataHeaderFlyweight header, final AtomicBuffer buffer,
                            final long length, final InetSocketAddress srcAddr)
    {
        final long channelId = header.channelId();

        final Subscription subscription = subscriptionByChannelIdMap.get(channelId);
        if (null == subscription)
        {
            return;  // not interested in this channel at all
        }

        final long sessionId = header.sessionId();
        final long termId = header.termId();
        final SubscribedSession subscribedSession = subscription.getSubscribedSession(sessionId);
        if (null != subscribedSession)
        {
            if (header.frameLength() > DataHeaderFlyweight.HEADER_LENGTH)
            {
                subscribedSession.rebuildBuffer(header, buffer, length);
            }
        }
        else
        {
            // new session, so make it here and save srcAddr
            subscription.createSubscribedSession(sessionId, srcAddr);

            // ask conductor thread to create buffer for destination, sessionId, channelId, and termId
            // NB: this only needs to happen the first time, since we use status to detect rollovers
            conductorProxy.createTermBuffer(destination(), sessionId, channelId, termId);
        }
    }

    public void onStatusMessageFrame(final StatusMessageFlyweight header, final AtomicBuffer buffer,
                                     final long length, final InetSocketAddress srcAddr)
    {
        // this should be on the data channel and shouldn't include SMs, so ignore.
    }

    public void onNakFrame(final NakFlyweight header, final AtomicBuffer buffer,
                           final long length, final InetSocketAddress srcAddr)
    {
        // this should be on the data channel and shouldn't include Naks, so ignore.
    }

    public void onSubscriptionReady(final NewReceiveBufferEvent event, final LossHandler lossHandler)
    {
        final Subscription subscription = subscriptionByChannelIdMap.get(event.channelId());
        if (null == subscription)
        {
            throw new IllegalStateException("channel not found");
        }

        final SubscribedSession subscriberSession = subscription.getSubscribedSession(event.sessionId());
        if (null == subscriberSession)
        {
            throw new IllegalStateException("session not found");
        }

        // for unicast, do the sending of NAKs on the DataFrameHandler
        lossHandler.sendNakHandler(
            (termId, termOffset, length) -> sendNak(subscriberSession, (int)termId, termOffset, length));

        subscriberSession.termBuffer(event.termId(), event.buffer(), lossHandler);

        // now we are all setup, so send an SM to allow the source to send if it is waiting
        // TODO: grab initial term offset from data and store in subscriberSession somehow (per TermID)
        // TODO: need a strategy object to track the initial receiver window to send in the SMs.
        sendStatusMessage(0, 1000, event.termId(), subscriberSession, subscription);
    }

    private int sendStatusMessage(final int termOffset,
                                  final int window,
                                  final long termId,
                                  final SubscribedSession subscribedSession,
                                  final Subscription subscription)
    {
        statusMessageFlyweight.wrap(writeSmBuffer, 0);
        statusMessageFlyweight.sessionId(subscribedSession.sessionId())
                              .channelId(subscription.channelId())
                              .termId(termId)
                              .highestContiguousTermOffset(termOffset)
                              .receiverWindow(window)
                              .headerType(HeaderFlyweight.HDR_TYPE_SM)
                              .frameLength(StatusMessageFlyweight.HEADER_LENGTH)
                              .flags((byte)0)
                              .version(HeaderFlyweight.CURRENT_VERSION);

        sendSmBuffer.position(0);
        sendSmBuffer.limit(StatusMessageFlyweight.HEADER_LENGTH);

        try
        {
            return transport.sendTo(sendSmBuffer, subscribedSession.sourceAddress());
        }
        catch (final Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public void sendNak(final SubscribedSession session, final int termId, final int termOffset, final int length)
    {
        nakHeader.wrap(writeNakBuffer, 0);
        nakHeader.channelId(session.channelId())
                 .sessionId(session.sessionId())
                 .termId(termId)
                 .termOffset(termOffset)
                 .length(length)
                 .frameLength(NakFlyweight.HEADER_LENGTH)
                 .headerType(HeaderFlyweight.HDR_TYPE_NAK)
                 .flags((byte)0)
                 .version(HeaderFlyweight.CURRENT_VERSION);

        sendNakBuffer.position(0);
        sendNakBuffer.limit(nakHeader.frameLength());

//        System.out.println("sendNak " + termId + " " + length + "@" + termOffset + " " + sendNakBuffer.remaining());
        try
        {
            if (transport.sendTo(sendNakBuffer, session.sourceAddress()) < nakHeader.frameLength())
            {
                throw new IllegalStateException("could not send all of NAK");
            }
        }
        catch (final Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }
}