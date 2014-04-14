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
package uk.co.real_logic.aeron.util.concurrent.logbuffer;

import uk.co.real_logic.aeron.util.BitUtil;

import static java.lang.Integer.valueOf;

/**
 * Description of the structure for message framing in a log buffer.
 * All messages are logged in frames that have a minimum header layout as follows plus a reserve then
 * the encoded message follows:
 *
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |  Version      |B|E| Flags     |             Type              |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-------------------------------+
 *  |R|                       Frame Length                          |
 *  +-+-------------------------------------------------------------+
 *  |R|                       Term Offset                           |
 *  +-+-------------------------------------------------------------+
 *  |                      Additional Fields                       ...
 * ...                                                              |
 *  +---------------------------------------------------------------+
 *  |                        Encoded Message                       ...
 * ...                                                              |
 *  +---------------------------------------------------------------+
 * </pre>
 *
 * The (B)egin and (E)nd flags are used for message fragmentation. R is for reserved bit.
 * Both are set for a message that does not span frames.
 *
 */
public class FrameDescriptor
{
    /** Alignment as a multiple of bytes for each frame. */
    public static final int FRAME_ALIGNMENT = BitUtil.CACHE_LINE_SIZE;

    /** Word alignment for fields. */
    public static final int WORD_ALIGNMENT = BitUtil.SIZE_OF_LONG;

    /** Beginning fragment of a frame. */
    public static final byte BEGIN_FRAG = (byte)0b1000_0000;

    /** End fragment of a frame. */
    public static final byte END_FRAG = (byte)0b0100_0000;

    /** End fragment of a frame. */
    public static final byte UNFRAGMENTED = (byte)(BEGIN_FRAG | END_FRAG);

    /** Length in bytes for the base header fields. */
    public static final int BASE_HEADER_LENGTH = 12;

    /** Offset within a frame at which the version field begins */
    public static final int VERSION_OFFSET = 0;

    /** Offset within a frame at which the flags field begins */
    public static final int FLAGS_OFFSET = 1;

    /** Offset within a frame at which the type field begins */
    public static final int TYPE_OFFSET = 2;

    /** Offset within a frame at which the length field begins */
    public static final int LENGTH_OFFSET = 4;

    /** Offset within a frame at which the term offset field begins */
    public static final int TERM_OFFSET = 8;

    /**
     * Calculate the maximum supported message length for a buffer of given capacity.
     *
     * @param capacity of the log buffer.
     * @return the maximum supported size for a message.
     */
    public static int calculateMaxMessageLength(final int capacity)
    {
        return Math.min(capacity / 8, 1 << 16);
    }

    /**
     * Check the the default header is greater than or equal in size to
     * {@link #BASE_HEADER_LENGTH} and a multiple of {@link #WORD_ALIGNMENT}.
     *
     * @param length to check.
     * @throws IllegalStateException if the default header is invalid.
     */
    public static void checkHeaderLength(final int length)
    {
        if (length < BASE_HEADER_LENGTH)
        {
            final String s = String.format("Frame header length must not be less than %d, length=%d",
                                           valueOf(BASE_HEADER_LENGTH), valueOf(length));
            throw new IllegalStateException(s);
        }

        if (length % WORD_ALIGNMENT != 0)
        {
            final String s = String.format("Frame header length must be a multiple of %d, length=%d",
                                           valueOf(WORD_ALIGNMENT), valueOf(length));
            throw new IllegalStateException(s);
        }
    }

    /**
     * Check the max frame length is a multiple of {@link #FRAME_ALIGNMENT}
     *
     * @param length to be applied to all logged frames.
     * @throws IllegalStateException if not a multiple of {@link #FRAME_ALIGNMENT}
     */
    public static void checkMaxFrameLength(final int length)
    {
        if (length % FRAME_ALIGNMENT != 0)
        {
            final String s = String.format("Max frame length must be a multiple of %d, length=%d",
                                           valueOf(FRAME_ALIGNMENT), valueOf(length));
            throw new IllegalStateException(s);
        }
    }

    /**
     * The buffer offset at which the version field begins.
     *
     * @param frameOffset at which the frame begins.
     * @return the offset at which the version field begins.
     */
    public static int versionOffset(final int frameOffset)
    {
        return frameOffset + VERSION_OFFSET;
    }

    /**
     * The buffer offset at which the flags field begins.
     *
     * @param frameOffset at which the frame begins.
     * @return the offset at which the flags field begins.
     */
    public static int flagsOffset(final int frameOffset)
    {
        return frameOffset + FLAGS_OFFSET;
    }

    /**
     * The buffer offset at which the type field begins.
     *
     * @param frameOffset at which the frame begins.
     * @return the offset at which the type field begins.
     */
    public static int typeOffset(final int frameOffset)
    {
        return frameOffset + TYPE_OFFSET;
    }

    /**
     * The buffer offset at which the length field begins.
     *
     * @param frameOffset at which the frame begins.
     * @return the offset at which the length field begins.
     */
    public static int lengthOffset(final int frameOffset)
    {
        return frameOffset + LENGTH_OFFSET;
    }

    /**
     * The buffer offset at which the term offset field begins.
     *
     * @param frameOffset at which the frame begins.
     * @return the offset at which the term offset field begins.
     */
    public static int termOffsetOffset(final int frameOffset)
    {
        return frameOffset + TERM_OFFSET;
    }
}