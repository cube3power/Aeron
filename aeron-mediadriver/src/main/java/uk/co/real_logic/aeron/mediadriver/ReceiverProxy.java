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

import uk.co.real_logic.aeron.util.command.QualifiedMessageFlyweight;
import uk.co.real_logic.aeron.util.command.SubscriberMessageFlyweight;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBuffer;

import java.nio.ByteBuffer;
import java.util.Queue;

import static uk.co.real_logic.aeron.util.command.ControlProtocolEvents.*;

/**
 * Proxy for writing into the Receiver Thread's command buffer.
 */
public class ReceiverProxy
{
    private static final int WRITE_BUFFER_CAPACITY = 256;

    private final RingBuffer commandBuffer;
    private final Queue<NewReceiveBufferEvent> newBufferEventQueue;
    private final AtomicBuffer writeBuffer = new AtomicBuffer(ByteBuffer.allocate(WRITE_BUFFER_CAPACITY));
    private final SubscriberMessageFlyweight subscriberMessage = new SubscriberMessageFlyweight();
    private final QualifiedMessageFlyweight qualifiedMessage = new QualifiedMessageFlyweight();

    public ReceiverProxy(final RingBuffer commandBuffer,
                         final Queue<NewReceiveBufferEvent> newBufferEventQueue)
    {
        this.commandBuffer = commandBuffer;
        this.newBufferEventQueue = newBufferEventQueue;
    }

    public void newSubscriber(final String destination, final long[] channelIdList)
    {
        addReceiver(ADD_SUBSCRIBER, destination, channelIdList);
    }

    public void removeSubscriber(final String destination, final long[] channelIdList)
    {
        addReceiver(REMOVE_SUBSCRIBER, destination, channelIdList);
    }

    private void addReceiver(final int msgTypeId, final String destination, final long[] channelIdList)
    {
        subscriberMessage.wrap(writeBuffer, 0);
        subscriberMessage.channelIds(channelIdList);
        subscriberMessage.destination(destination);
        commandBuffer.write(msgTypeId, writeBuffer, 0, subscriberMessage.length());
    }

    public void termBufferCreated(final String destination,
                                  final long sessionId,
                                  final long channelId,
                                  final long termId)
    {
        qualifiedMessage.wrap(writeBuffer, 0);
        qualifiedMessage.sessionId(sessionId);
        qualifiedMessage.channelId(channelId);
        qualifiedMessage.termId(termId);
        qualifiedMessage.destination(destination);
        commandBuffer.write(NEW_RECEIVE_BUFFER_NOTIFICATION, writeBuffer, 0, qualifiedMessage.length());
    }

    public boolean newReceiveBuffer(final NewReceiveBufferEvent e)
    {
        return newBufferEventQueue.offer(e);
    }
}